package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorLifecycle;
import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.application.job.JobSubmissionService;
import io.github.rajami1205.osimulator.application.job.JobScheduler;
import io.github.rajami1205.osimulator.application.process.AdmissionResult;
import io.github.rajami1205.osimulator.application.process.Dispatcher;
import io.github.rajami1205.osimulator.application.process.KeyboardCompletionService;
import io.github.rajami1205.osimulator.application.process.ProcessCompletionService;
import io.github.rajami1205.osimulator.application.process.CompletedProcessRecord;
import io.github.rajami1205.osimulator.application.process.PendingKeyboardRequest;
import io.github.rajami1205.osimulator.model.scheduling.SuspendedReadyQueue;
import io.github.rajami1205.osimulator.application.process.ProcessResourceRegistry;
import io.github.rajami1205.osimulator.application.process.ProcessSwapService;
import io.github.rajami1205.osimulator.application.process.SwapResult;
import io.github.rajami1205.osimulator.application.process.ProcessAdmissionService;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import io.github.rajami1205.osimulator.model.scheduling.ReadyQueue;
import io.github.rajami1205.osimulator.model.scheduling.ProcessScheduler;
import io.github.rajami1205.osimulator.model.scheduling.FcfsProcessScheduler;
import io.github.rajami1205.osimulator.model.job.Job;
import io.github.rajami1205.osimulator.model.job.JobList;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.cpu.CpuRegisters;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.execution.ExecutionProgress;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.memory.MainMemory;
import io.github.rajami1205.osimulator.model.memory.MemoryContent;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import io.github.rajami1205.osimulator.model.io.ScreenDevice;
import io.github.rajami1205.osimulator.model.io.KeyboardDevice;
import io.github.rajami1205.osimulator.model.filesystem.SimulatedFileSystem;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Lifecycle/session facade over the single-CPU multiprocess runtime. */
public final class SimulatorOrchestrator {
    private final ProgramLoader programLoader;
    private final ExecutionEngine executionEngine;
    private final SimulatorLifecycle lifecycle = new SimulatorLifecycle();
    private MainMemory memory;
    private SecondaryStorage secondaryStorage;
    private SimulatedFileSystem filesystem;
    private JobList jobList;
    private JobSubmissionService jobSubmissionService;
    private ProcessTable processTable;
    private ProcessAdmissionService processAdmissionService;
    private ProcessResourceRegistry processResources;
    private ProcessSwapService processSwapService;
    private JobScheduler jobScheduler;
    private ReadyQueue readyQueue;
    private ProcessScheduler processScheduler;
    private CpuRegisters<Instruction> cpu;
    private ExecutionProgress executionProgress;
    private ScreenDevice screen;
    private KeyboardDevice keyboard;
    private Dispatcher dispatcher;
    private KeyboardCompletionService keyboardCompletion;
    private ProcessCompletionService processCompletion;
    private MultiprocessRuntime runtime;
    private long compatibilitySequence;
    private SimulatorConfiguration configuration;

    // Recibe los servicios que coordinan la carga y ejecución.
    public SimulatorOrchestrator(
            ProgramLoader programLoader,
            ExecutionEngine executionEngine
    ) {
        this.programLoader = Objects.requireNonNull(programLoader, "programLoader must not be null");
        this.executionEngine = Objects.requireNonNull(executionEngine, "executionEngine must not be null");
    }

    // Crea los recursos válidos antes de publicar la sesión inicializada.
    public void initialize(SimulatorConfiguration configuration) {
        requireState("initialize", SimulatorState.CONFIGURING);
        Objects.requireNonNull(configuration, "configuration must not be null");
        MainMemory newMemory = new MainMemory(configuration.mainMemory());
        CpuRegisters<Instruction> newCpu = new CpuRegisters<>();
        ExecutionProgress newProgress = new ExecutionProgress();
        ScreenDevice newScreen = new ScreenDevice();
        KeyboardDevice newKeyboard = new KeyboardDevice();
        SecondaryStorage newStorage = new SecondaryStorage(
                configuration.secondaryStoragePositions(), configuration.virtualMemoryPositions());
        JobList newJobs = new JobList();
        JobSubmissionService newSubmissionService = new JobSubmissionService(newStorage, newJobs);
        ProcessTable newProcesses = new ProcessTable();
        ReadyQueue newReadyQueue = new ReadyQueue();
        ProcessResourceRegistry newResources = new ProcessResourceRegistry();
        ProcessSwapService newSwap = new ProcessSwapService(newMemory, newStorage, newProcesses, newResources, newReadyQueue);
        ProcessAdmissionService newAdmission = new ProcessAdmissionService(
                newJobs, newStorage, newMemory, newProcesses, programLoader, newReadyQueue, newResources);
        JobScheduler newScheduler = new JobScheduler(newJobs, newAdmission);
        ProcessScheduler newProcessScheduler = new FcfsProcessScheduler(newReadyQueue, newProcesses);
        lifecycle.initialize();
        memory = newMemory;
        cpu = newCpu;
        executionProgress = newProgress;
        screen = newScreen;
        keyboard = newKeyboard;
        secondaryStorage = newStorage;
        filesystem = new SimulatedFileSystem(newStorage);
        jobList = newJobs;
        jobSubmissionService = newSubmissionService;
        processTable = newProcesses;
        processAdmissionService = newAdmission;
        processResources = newResources;
        processSwapService = newSwap;
        jobScheduler = newScheduler;
        readyQueue = newReadyQueue;
        processScheduler = newProcessScheduler;
        var suspended = new SuspendedReadyQueue();
        dispatcher = new Dispatcher(cpu, processTable, readyQueue, processResources);
        keyboardCompletion = new KeyboardCompletionService(keyboard, processTable, processResources, readyQueue, suspended);
        processCompletion = new ProcessCompletionService(memory, secondaryStorage, processTable, processResources,
                readyQueue, suspended, keyboardCompletion, dispatcher);
        runtime = new MultiprocessRuntime(dispatcher, executionEngine, executionProgress, cpu, memory, filesystem,
                screen, keyboard, keyboardCompletion, processCompletion, processSwapService, jobScheduler,
                jobList, processTable, processResources, readyQueue, suspended, processScheduler);
        this.configuration = configuration;
    }

    /** Configuración inmutable de la sesión, ausente antes de Initialize y después de Reset. */
    public Optional<SimulatorConfiguration> configuration() {
        return Optional.ofNullable(configuration);
    }

    /** Presenta trabajo y prepara el workload sin crear todavía un proceso. */
    public Job submitProgram(ProgramImage program) {
        requirePreparedState("submitProgram");
        var job = jobSubmissionService.submit(program);
        if (lifecycle.state() == SimulatorState.INITIALIZED) lifecycle.markProgramLoaded();
        return job;
    }

    /** Vista histórica inmutable; vacía cuando no existe sesión. */
    public List<Job> jobs() {
        return jobList == null ? List.of() : jobList.entries();
    }

    /** Admits at most one prepared Job without dispatching or consuming ticks. */
    public Optional<AdmissionResult> attemptNextAdmission() {
        requirePreparedState("attemptNextAdmission");
        return jobScheduler.attemptNextAdmission();
    }

    /** Consulta el próximo candidato sin consumir READY ni activar la CPU. */
    public Optional<Integer> selectNextReadyProcess() {
        requirePreparedState("selectNextReadyProcess");
        return processScheduler.selectNext();
    }

    public SwapResult swapOut(int processId) {
        requireSwapSession();
        var result = runtime.swapOut(processId);
        if (result instanceof SwapResult.Completed) {
            try { runtime.retryAdmissionAfterRelease(); }
            catch (RuntimeException failure) { throw runtimeFailure(failure); }
        }
        completePendingKeyboardInput();
        return result;
    }

    public SwapResult swapIn(int processId) {
        requireSwapSession();
        var result = runtime.swapIn(processId);
        completePendingKeyboardInput();
        return result;
    }

    private void requireSwapSession() {
        if (runtime == null || (lifecycle.state() != SimulatorState.INITIALIZED
                && lifecycle.state() != SimulatorState.PROGRAM_LOADED && lifecycle.state() != SimulatorState.RUNNING
                && lifecycle.state() != SimulatorState.PAUSED)) throw new IllegalStateException("Swapping requires a live session");
    }

    private void requirePreparedState(String operation) {
        if (lifecycle.state() != SimulatorState.INITIALIZED && lifecycle.state() != SimulatorState.PROGRAM_LOADED) {
            throw new IllegalStateException(operation + " requires a prepared, unstarted workload");
        }
    }

    /** Compatibility submission adapter: never creates a private executable PCB. */
    public void loadProgram(List<Instruction> instructions) {
        requirePreparedState("loadProgram");
        var existingNames = secondaryStorage.entries().stream().map(entry -> entry.name()).toList();
        String name;
        do { name = "compatibility-program-" + (++compatibilitySequence); }
        while (existingNames.contains(name));
        submitProgram(new ProgramImage(name, instructions));
    }

    public void start() {
        requireState("start", SimulatorState.PROGRAM_LOADED);
        try {
            runtime.start();
            lifecycle.startExecution();
        } catch (RuntimeException failure) { throw runtimeFailure(failure); }
    }

    public RuntimeStepResult step() {
        requireState("step", SimulatorState.RUNNING);
        try {
            var result = runtime.step();
            finishIfComplete(result.status());
            return result;
        } catch (RuntimeException failure) { throw runtimeFailure(failure); }
    }

    public RuntimeStatus runtimeStatus() {
        if (runtime == null) return RuntimeStatus.WAITING_FOR_CAPACITY;
        try { return runtime.status(); }
        catch (RuntimeException failure) { throw runtimeFailure(failure); }
    }

    private void finishIfComplete(RuntimeStatus status) {
        if (status == RuntimeStatus.FINISHED && lifecycle.state() == SimulatorState.RUNNING) lifecycle.finishExecution();
    }

    private ExecutionEngineException runtimeFailure(RuntimeException failure) {
        lifecycle.markError();
        return failure instanceof ExecutionEngineException execution ? execution
                : new ExecutionEngineException("Runtime coordination failed: " + failure.getMessage(), failure);
    }

    public List<CompletedProcessRecord> completedProcesses() {
        return processCompletion == null ? List.of() : processCompletion.completed();
    }

    public List<PendingKeyboardRequest> pendingKeyboardRequests() {
        return keyboardCompletion == null ? List.of() : keyboardCompletion.pending();
    }

    public List<Integer> screenOutput() { return screen == null ? List.of() : screen.outputs(); }

    /** Global external wait, not merely the presence of one blocked process. */
    public boolean waitingForInput() { return runtimeStatus() == RuntimeStatus.WAITING_FOR_INPUT; }

    public void submitKeyboardInput(int value) {
        if (keyboard == null) throw new IllegalStateException("Keyboard requires an initialized session");
        keyboard.submit(value);
        completePendingKeyboardInput();
    }

    private void completePendingKeyboardInput() {
        if (lifecycle.state() == SimulatorState.RUNNING) {
            try {
                runtime.drainInput();
                finishIfComplete(runtime.status());
            } catch (RuntimeException failure) { throw runtimeFailure(failure); }
        }
    }

    // Pausa la sesión sin modificar el estado del proceso.
    public void pause() {
        lifecycle.pauseExecution();
    }

    // Devuelve la sesión pausada al estado RUNNING.
    public void resume() {
        lifecycle.resumeExecution();
        completePendingKeyboardInput();
    }

    // Descarta los recursos de sesión y devuelve la sesión a CONFIGURING.
    public void reset() {
        lifecycle.reset();
        memory = null;
        secondaryStorage = null;
        filesystem = null;
        jobList = null;
        jobSubmissionService = null;
        processTable = null;
        processAdmissionService = null;
        processResources = null;
        processSwapService = null;
        jobScheduler = null;
        readyQueue = null;
        processScheduler = null;
        cpu = null;
        executionProgress = null;
        screen = null;
        keyboard = null;
        dispatcher = null;
        keyboardCompletion = null;
        processCompletion = null;
        runtime = null;
        compatibilitySequence = 0;
        configuration = null;
    }

    /** One immutable read of the session. ERROR never revalidates a failed runtime. */
    public SimulatorSnapshot snapshot() {
        Optional<RuntimeStatus> status = Optional.empty();
        if (lifecycle.state() == SimulatorState.FINISHED) status = Optional.of(RuntimeStatus.FINISHED);
        else if (lifecycle.state() == SimulatorState.RUNNING || lifecycle.state() == SimulatorState.PAUSED) {
            try { status = Optional.of(runtime.status()); }
            catch (RuntimeException failure) { lifecycle.markError(); }
        }
        return SimulatorSnapshotMapper.map(lifecycle.state(), status, configuration, cpu,
                dispatcher == null ? Optional.empty() : dispatcher.owner(), memory, secondaryStorage,
                jobs(), processTable, processResources, readyQueue == null ? List.of() : readyQueue.entries(),
                runtime == null ? List.of() : runtime.suspendedReadyProcessIds(), pendingKeyboardRequests(),
                completedProcesses(), screenOutput());
    }

    Optional<String> contentText(MemoryContent content) {
        return SimulatorSnapshotMapper.memoryText(content);
    }

    // Rechaza operaciones que no corresponden al estado actual de la sesión.
    private void requireState(String operation, SimulatorState expected) {
        if (lifecycle.state() != expected) {
            throw new IllegalStateException("Cannot " + operation + " while simulator state is " + lifecycle.state());
        }
    }
}
