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
import java.time.Clock;
import io.github.rajami1205.osimulator.model.execution.CpuTickCounter;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.application.program.exception.ProgramLoadException;
import io.github.rajami1205.osimulator.model.storage.exception.StorageException;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryProtectionException;
import io.github.rajami1205.osimulator.model.memory.exception.InvalidMemoryReleaseException;
import io.github.rajami1205.osimulator.model.memory.exception.InvalidMemoryAddressException;
import java.util.OptionalLong;
import java.util.Objects;
import java.util.Optional;

/**
 * Facade de Application entre Presentation y la sesión multiproceso. Compone servicios, controla lifecycle
 * y expone snapshots sin delegar reglas de negocio a JavaFX.
 */
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

    private CpuTickCounter tickCounter;
    private final Clock realClock;

    /** Recibe loader y engine; usa Clock del sistema por defecto o el Clock inyectado para accounting real. */
    public SimulatorOrchestrator(
            ProgramLoader programLoader,
            ExecutionEngine executionEngine
    ) {
        this(programLoader, executionEngine, Clock.systemUTC());
    }

    /** Recibe loader y engine; usa Clock del sistema por defecto o el Clock inyectado para accounting real. */
    public SimulatorOrchestrator(ProgramLoader programLoader, ExecutionEngine executionEngine, Clock realClock) {
        this.realClock = Objects.requireNonNull(realClock, "realClock must not be null");
        this.programLoader = Objects.requireNonNull(programLoader, "programLoader must not be null");
        this.executionEngine = Objects.requireNonNull(executionEngine, "executionEngine must not be null");
    }

    /**
     * Desde CONFIGURING crea los recursos canónicos de sesión y conecta servicios, Clock y contador de
     * ticks; publica configuración inicializada.
     */
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
        tickCounter = new CpuTickCounter();
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
        dispatcher = new Dispatcher(cpu, processTable, readyQueue, processResources, memory);
        keyboardCompletion = new KeyboardCompletionService(keyboard, processTable, processResources, readyQueue, suspended);
        processCompletion = new ProcessCompletionService(memory, secondaryStorage, processTable, processResources,
                readyQueue, suspended, keyboardCompletion, dispatcher, realClock);
        runtime = new MultiprocessRuntime(dispatcher, executionEngine, executionProgress, cpu, memory, filesystem,
                screen, keyboard, keyboardCompletion, processCompletion, processSwapService, jobScheduler,
                jobList, processTable, processResources, readyQueue, suspended, processScheduler, tickCounter, realClock);
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

    /**
     * Intenta admitir el primer Job en estado preparado; convierte fallos internos de admisión en ERROR de
     * sesión.
     */
    public Optional<AdmissionResult> attemptNextAdmission() {
        requirePreparedState("attemptNextAdmission");
        try { return jobScheduler.attemptNextAdmission(); }
        catch (IllegalStateException | ProgramLoadException | StorageException | MemoryProtectionException
                | InvalidMemoryReleaseException | InvalidMemoryAddressException failure) { throw runtimeFailure(failure); }
    }

    /** Consulta el próximo candidato sin consumir READY ni activar la CPU. */
    public Optional<Integer> selectNextReadyProcess() {
        requirePreparedState("selectNextReadyProcess");
        return processScheduler.selectNext();
    }

    /**
     * Valida sesión y elegibilidad del PID antes del swap explícito; traduce fallos de integridad a ERROR
     * y reintenta admission tras liberar USER.
     */
    public SwapResult swapOut(int processId) {
        requireSwapSession();
        requireSwapCandidate(processId, true);
        SwapResult result;
        try { result = runtime.swapOut(processId); }
        catch (IllegalStateException | StorageException | MemoryProtectionException
                | InvalidMemoryReleaseException | InvalidMemoryAddressException failure) { throw runtimeFailure(failure); }
        if (result instanceof SwapResult.Completed) {
            try { runtime.retryAdmissionAfterRelease(); }
            catch (RuntimeException failure) { throw runtimeFailure(failure); }
        }
        completePendingKeyboardInput();
        return result;
    }

    /**
     * Valida sesión y PID suspended; delega transferencia y completion pendiente, traduciendo fallos de
     * integridad a ERROR.
     */
    public SwapResult swapIn(int processId) {
        requireSwapSession();
        requireSwapCandidate(processId, false);
        SwapResult result;
        try { result = runtime.swapIn(processId); }
        catch (IllegalStateException | StorageException | MemoryProtectionException
                | InvalidMemoryReleaseException | InvalidMemoryAddressException failure) { throw runtimeFailure(failure); }
        completePendingKeyboardInput();
        return result;
    }

    /**
     * Rechaza como precondición recuperable PID desconocido, owner o estado incompatible con la
     * transferencia solicitada.
     */
    private void requireSwapCandidate(int pid, boolean out) {
        var pcb = processTable.find(pid).orElseThrow(() -> new IllegalArgumentException("Unknown process: " + pid));
        boolean eligible = out ? pcb.state() == ProcessState.READY || pcb.state() == ProcessState.BLOCKED
                : pcb.state() == ProcessState.READY_SUSPENDED || pcb.state() == ProcessState.BLOCKED_SUSPENDED;
        if (!eligible || dispatcher.owner().orElse(null) == pcb) throw new IllegalStateException("Process is not eligible for requested swap");
    }

    /** Exige sesión preparada, RUNNING o PAUSED para permitir transferencias explícitas. */
    private void requireSwapSession() {
        if (runtime == null || (lifecycle.state() != SimulatorState.INITIALIZED
                && lifecycle.state() != SimulatorState.PROGRAM_LOADED && lifecycle.state() != SimulatorState.RUNNING
                && lifecycle.state() != SimulatorState.PAUSED)) throw new IllegalStateException("Swapping requires a live session");
    }

    /** Exige workload inicializado pero aún no iniciado para aceptar carga y admission explícita. */
    private void requirePreparedState(String operation) {
        if (lifecycle.state() != SimulatorState.INITIALIZED && lifecycle.state() != SimulatorState.PROGRAM_LOADED) {
            throw new IllegalStateException(operation + " requires a prepared, unstarted workload");
        }
    }

    /**
     * Adaptador compatible: crea un nombre libre y hace submission como Job; no fabrica un PCB ejecutable
     * privado.
     */
    public void loadProgram(List<Instruction> instructions) {
        requirePreparedState("loadProgram");
        var existingNames = secondaryStorage.entries().stream().map(entry -> entry.name()).toList();
        String name;
        do { name = "compatibility-program-" + (++compatibilitySequence); }
        while (existingNames.contains(name));
        submitProgram(new ProgramImage(name, instructions));
    }

    /** Inicia el workload desde PROGRAM_LOADED y habilita RUNNING; un fallo de coordinación marca ERROR. */
    public void start() {
        requireState("start", SimulatorState.PROGRAM_LOADED);
        try {
            runtime.start();
            lifecycle.startExecution();
        } catch (RuntimeException failure) { throw runtimeFailure(failure); }
    }

    /**
     * Desde RUNNING ejecuta como máximo un tick a través del runtime y finaliza lifecycle sólo al agotar
     * todo el workload.
     */
    public RuntimeStepResult step() {
        requireState("step", SimulatorState.RUNNING);
        try {
            var result = runtime.step();
            finishIfComplete(result.status());
            return result;
        } catch (RuntimeException failure) { throw runtimeFailure(failure); }
    }

    /**
     * Consulta disponibilidad global; sin sesión devuelve WAITING_FOR_CAPACITY y ante inconsistencia marca
     * ERROR.
     */
    public RuntimeStatus runtimeStatus() {
        if (runtime == null) return RuntimeStatus.WAITING_FOR_CAPACITY;
        try { return runtime.status(); }
        catch (RuntimeException failure) { throw runtimeFailure(failure); }
    }

    /** Marca FINISHED únicamente si el runtime agotó el workload y lifecycle sigue RUNNING. */
    private void finishIfComplete(RuntimeStatus status) {
        if (status == RuntimeStatus.FINISHED && lifecycle.state() == SimulatorState.RUNNING) lifecycle.finishExecution();
    }

    /** Marca ERROR global y conserva el error de ejecución o envuelve la causa de coordinación. */
    private ExecutionEngineException runtimeFailure(RuntimeException failure) {
        lifecycle.markError();
        return failure instanceof ExecutionEngineException execution ? execution
                : new ExecutionEngineException("Runtime coordination failed: " + failure.getMessage(), failure);
    }

    /** Devuelve historial inmutable de la sesión, vacío antes de Initialize o después de Reset. */
    public List<CompletedProcessRecord> completedProcesses() {
        return processCompletion == null ? List.of() : processCompletion.completed();
    }

    /** Expone solicitudes FIFO inmutables sin entregar el dispositivo ni PCBs a Presentation. */
    public List<PendingKeyboardRequest> pendingKeyboardRequests() {
        return keyboardCompletion == null ? List.of() : keyboardCompletion.pending();
    }

    /** Devuelve salidas numéricas inmutables o una lista vacía cuando no hay sesión. */
    public List<Integer> screenOutput() { return screen == null ? List.of() : screen.outputs(); }

    /** Indica espera global de input, no simplemente la existencia de un proceso BLOCKED. */
    public boolean waitingForInput() { return runtimeStatus() == RuntimeStatus.WAITING_FOR_INPUT; }

    /**
     * Valida lifecycle activo y encola input; completa solicitudes sólo en RUNNING, conservando la cola
     * durante Pause.
     */
    public void submitKeyboardInput(int value) {
        var state = lifecycle.state();
        if (state != SimulatorState.INITIALIZED && state != SimulatorState.PROGRAM_LOADED
                && state != SimulatorState.RUNNING && state != SimulatorState.PAUSED) {
            throw new IllegalStateException("Keyboard input requires an active session");
        }
        keyboard.submit(value);
        completePendingKeyboardInput();
    }

    /**
     * Drena eventos sólo en RUNNING, sin ticks de CPU, y actualiza FINISHED o ERROR global cuando
     * corresponda.
     */
    private void completePendingKeyboardInput() {
        if (lifecycle.state() == SimulatorState.RUNNING) {
            try {
                runtime.drainInput();
                finishIfComplete(runtime.status());
            } catch (RuntimeException failure) { throw runtimeFailure(failure); }
        }
    }

    /** Pausa la sesión sin modificar el estado del proceso. */
    public void pause() {
        lifecycle.pauseExecution();
    }

    /** Devuelve la sesión pausada al estado RUNNING. */
    public void resume() {
        lifecycle.resumeExecution();
        completePendingKeyboardInput();
    }

    /** Descarta los recursos de sesión y devuelve la sesión a CONFIGURING. */
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
        tickCounter = null;
        compatibilitySequence = 0;
        configuration = null;
    }

    /**
     * Construye una observación inmutable; una inconsistencia puede marcar ERROR, cuyo runtime no se
     * revalida al volver a consultar.
     */
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
                completedProcesses(), screenOutput(), tickCounter == null ? OptionalLong.empty() : OptionalLong.of(tickCounter.current()));
    }

    /** Delega el texto descriptivo de memoria al mapper sin exponer entidades mutables. */
    Optional<String> contentText(MemoryContent content) {
        return SimulatorSnapshotMapper.memoryText(content);
    }

    /** Rechaza operaciones que no corresponden al estado actual de la sesión. */
    private void requireState(String operation, SimulatorState expected) {
        if (lifecycle.state() != expected) {
            throw new IllegalStateException("Cannot " + operation + " while simulator state is " + lifecycle.state());
        }
    }
}
