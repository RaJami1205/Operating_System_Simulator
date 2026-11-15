package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Multilevel Feedback Queue (Stallings "Feedback", q = 2^i) con parámetros fijos: 3 niveles con quantum
 * 1, 2 y 4 (nivel 0 = mayor prioridad) y sin priority boost.
 *
 * <ul>
 *   <li>Un proceso nuevo entra al final del nivel 0. Corre la cabeza del nivel más alto no vacío.</li>
 *   <li>Sólo las instrucciones completadas consumen quantum. No hay expulsión a mitad de quantum, aunque
 *       llegue un proceso de nivel más alto.</li>
 *   <li>Al agotar el quantum: si no hay otro READY en la CPU, el owner sigue en su nivel con quantum nuevo.
 *       Si lo hay, baja un nivel (el último no baja más: RR) y es expulsado al final de su nuevo nivel,
 *       salvo que todos los que esperan estén en niveles más bajos: entonces sigue corriendo en su nuevo
 *       nivel con quantum nuevo.</li>
 *   <li>Un proceso que vuelve de BLOCKED o de suspensión conserva su nivel y entra al final con quantum
 *       nuevo. Las llegadas de un tick se encolan antes que el owner expulsado en ese mismo tick.</li>
 * </ul>
 *
 * <p>El nivel es estado interno del scheduler (no se guarda en el PCB).
 */
public final class MlfqProcessScheduler implements ProcessScheduler {

    /** Quantum de cada nivel; el índice es el nivel (0 = mayor prioridad). */
    public static final List<Integer> LEVEL_QUANTA = List.of(1, 2, 4);

    private static final int LAST_LEVEL = LEVEL_QUANTA.size() - 1;

    private final CpuId cpuId;
    private final ReadyQueue readyQueue;
    private final ProcessTable processes;

    private final Map<Integer, Integer> levelByPid = new HashMap<>();
    /** Procesos READY esperando, por nivel (el owner no está aquí). */
    private final List<Deque<Integer>> levels = new ArrayList<>();
    private Integer ownerPid;
    private int completedInQuantum;

    public MlfqProcessScheduler(CpuId cpuId, ReadyQueue readyQueue, ProcessTable processes) {
        this.cpuId = Objects.requireNonNull(cpuId, "cpuId must not be null");
        this.readyQueue = Objects.requireNonNull(readyQueue, "readyQueue must not be null");
        this.processes = Objects.requireNonNull(processes, "processes must not be null");
        readyQueue.requireBinding(cpuId, processes);
        for (int level = 0; level <= LAST_LEVEL; level++) {
            levels.add(new ArrayDeque<>());
        }
    }

    @Override
    public void onSchedulingPoint(long now) {
        forgetReleasedOwner();
        forgetTerminatedProcesses();
        dropProcessesThatLeftReady();
        enqueueArrivalsAndReturns();
    }

    @Override
    public Optional<Integer> selectNext() {
        OptionalInt bestLevel = highestWaitingLevel();
        if (bestLevel.isEmpty()) {
            return Optional.empty();
        }
        int head = levels.get(bestLevel.getAsInt()).peekFirst();
        validated(head);
        return Optional.of(head);
    }

    @Override
    public boolean shouldPreempt(ProcessControlBlock owner) {
        Objects.requireNonNull(owner, "owner must not be null");
        int ownerId = owner.processId();
        int ownerLevel = levelOf(ownerId);
        int ownerQuantum = LEVEL_QUANTA.get(ownerLevel);
        if (completedInQuantum < ownerQuantum) {
            return false;
        }

        OptionalInt bestWaitingLevel = highestWaitingLevel();
        if (bestWaitingLevel.isEmpty()) {
            // Solo en la CPU: no se degrada, sigue con quantum nuevo.
            completedInQuantum = 0;
            return false;
        }

        int demotedLevel = Math.min(ownerLevel + 1, LAST_LEVEL);
        levelByPid.put(ownerId, demotedLevel);
        boolean someoneWaitsAtSameOrHigherLevel = bestWaitingLevel.getAsInt() <= demotedLevel;
        if (!someoneWaitsAtSameOrHigherLevel) {
            // Degradado, pero sigue siendo el mejor: continúa en su nuevo nivel.
            completedInQuantum = 0;
            return false;
        }

        levels.get(demotedLevel).addLast(ownerId);
        ownerPid = null;
        return true;
    }

    @Override
    public void onDispatched(int pid) {
        int level = levelOf(pid);
        levels.get(level).remove(pid);
        ownerPid = pid;
        completedInQuantum = 0;
    }

    @Override
    public void onInstructionCompleted(int pid) {
        completedInQuantum++;
    }

    private OptionalInt highestWaitingLevel() {
        for (int level = 0; level <= LAST_LEVEL; level++) {
            if (!levels.get(level).isEmpty()) {
                return OptionalInt.of(level);
            }
        }
        return OptionalInt.empty();
    }

    /** Un PID sin nivel registrado es nuevo: nivel 0. */
    private int levelOf(int pid) {
        return levelByPid.getOrDefault(pid, 0);
    }

    /** El owner terminó o se bloqueó en el tick anterior: el Dispatcher ya liberó la CPU. */
    private void forgetReleasedOwner() {
        if (ownerPid == null) {
            return;
        }
        Optional<ProcessControlBlock> owner = processes.find(ownerPid);
        boolean stillRunning = owner.isPresent() && owner.get().state() == ProcessState.RUNNING;
        if (!stillRunning) {
            ownerPid = null;
        }
    }

    private void forgetTerminatedProcesses() {
        levelByPid.keySet().removeIf(pid -> {
            Optional<ProcessControlBlock> pcb = processes.find(pid);
            return pcb.isEmpty() || pcb.get().state() == ProcessState.TERMINATED;
        });
    }

    /** Un proceso que dejó la ReadyQueue (p. ej. suspendido) sale de su cola hasta que regrese. */
    private void dropProcessesThatLeftReady() {
        List<Integer> readyEntries = readyQueue.entries();
        for (Deque<Integer> level : levels) {
            level.removeIf(pid -> !readyEntries.contains(pid));
        }
    }

    /** Nuevos entran al nivel 0; los que regresan a READY, al final de su nivel. */
    private void enqueueArrivalsAndReturns() {
        for (int pid : readyQueue.entries()) {
            validated(pid);
            boolean alreadyQueued = levels.stream().anyMatch(level -> level.contains(pid));
            if (alreadyQueued) {
                continue;
            }
            int level = levelOf(pid);
            levelByPid.put(pid, level);
            levels.get(level).addLast(pid);
        }
    }

    private ProcessControlBlock validated(int pid) {
        ProcessControlBlock pcb = processes.find(pid).orElseThrow(
                () -> new IllegalStateException("Queued PID is not registered: " + pid));
        if (pcb.state() != ProcessState.READY || !pcb.cpuAffinity().equals(cpuId)) {
            throw new IllegalStateException("Queued PID is not READY on this CPU: " + pid);
        }
        return pcb;
    }
}
