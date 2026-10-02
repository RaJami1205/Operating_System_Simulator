package io.github.rajami1205.osimulator.model.execution;

import io.github.rajami1205.osimulator.model.cpu.CpuRegisters;
import io.github.rajami1205.osimulator.model.cpu.exception.InvalidRegisterValueException;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.instruction.AddInstruction;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.instruction.LoadInstruction;
import io.github.rajami1205.osimulator.model.instruction.MovInstruction;
import io.github.rajami1205.osimulator.model.instruction.StoreInstruction;
import io.github.rajami1205.osimulator.model.instruction.SubInstruction;
import io.github.rajami1205.osimulator.model.instruction.IncInstruction;
import io.github.rajami1205.osimulator.model.instruction.DecInstruction;
import io.github.rajami1205.osimulator.model.instruction.SwapInstruction;
import io.github.rajami1205.osimulator.model.instruction.operand.ImmediateOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;
import io.github.rajami1205.osimulator.model.memory.MainMemory;
import io.github.rajami1205.osimulator.model.memory.MemoryAllocation;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryProtectionException;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import java.util.Objects;
import io.github.rajami1205.osimulator.model.filesystem.SimulatedFileSystem;
import io.github.rajami1205.osimulator.model.filesystem.FileSystemException;
import io.github.rajami1205.osimulator.model.cpu.ServiceRegister;
import io.github.rajami1205.osimulator.model.cpu.TextRegisterValue;
import io.github.rajami1205.osimulator.model.instruction.operand.TextOperand;
import io.github.rajami1205.osimulator.model.io.ScreenDevice;
import io.github.rajami1205.osimulator.model.io.KeyboardDevice;
import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.InterruptInstruction;
import io.github.rajami1205.osimulator.model.instruction.operand.InterruptVector;
import io.github.rajami1205.osimulator.model.instruction.CmpInstruction;
import io.github.rajami1205.osimulator.model.instruction.JmpInstruction;
import io.github.rajami1205.osimulator.model.instruction.JeInstruction;
import io.github.rajami1205.osimulator.model.instruction.JneInstruction;
import io.github.rajami1205.osimulator.model.instruction.ParamInstruction;
import io.github.rajami1205.osimulator.model.instruction.PushInstruction;
import io.github.rajami1205.osimulator.model.instruction.PopInstruction;
import io.github.rajami1205.osimulator.model.instruction.operand.BranchDisplacement;
import io.github.rajami1205.osimulator.model.cpu.ConditionFlags;
import io.github.rajami1205.osimulator.model.process.exception.ProcessStackOverflowException;
import io.github.rajami1205.osimulator.model.process.exception.ProcessStackUnderflowException;

/**
 * Consume ticks sin conservar estado de sesión dentro del engine.
 */
public final class ExecutionEngine {
    private sealed interface SemanticOutcome permits Continue, Terminate, WaitForKeyboard { }
    private record Continue(int nextLogicalPc) implements SemanticOutcome { }
    private record Terminate() implements SemanticOutcome { }
    private record WaitForKeyboard() implements SemanticOutcome { }

    // Consume un tick; los efectos semánticos se aplican únicamente en el tick final.
    public TickResult executeTick(
            SimulatedFileSystem filesystem,
            ScreenDevice screen,
            KeyboardDevice keyboard,
            MainMemory memory,
            CpuRegisters<Instruction> cpu,
            ProcessControlBlock pcb,
            ExecutionProgress progress,
            MemoryAllocation allocation
    ) {
        Objects.requireNonNull(memory, "memory must not be null");
        Objects.requireNonNull(cpu, "cpu must not be null");
        Objects.requireNonNull(pcb, "pcb must not be null");
        Objects.requireNonNull(progress, "progress must not be null");

        Objects.requireNonNull(filesystem, "filesystem must not be null");
        Objects.requireNonNull(screen, "screen must not be null");
        Objects.requireNonNull(keyboard, "keyboard must not be null");
        validateExecutableState(pcb);
        progress.validateContext(memory, cpu, pcb, allocation);

        int currentProgramCounter = pcb.programCounter();
        int instructionCount = pcb.instructionCount();
        if (currentProgramCounter == instructionCount) {
            cpu.setProgramCounter(instructionCount);
            pcb.changeState(ProcessState.TERMINATED);
            return TickResult.PROGRAM_FINISHED;
        }

        try {
            memory.validateUserAllocation(allocation, pcb.memoryBounds());
        } catch (RuntimeException exception) {
            throw new ExecutionEngineException("Invalid process allocation", exception);
        }
        if (progress.isIdle()) {
            Instruction instruction;
            try {
                instruction = memory.readInstruction(allocation, currentProgramCounter);
            } catch (MemoryProtectionException exception) {
                throw new ExecutionEngineException("Unable to fetch process instruction", exception);
            }

            cpu.setProgramCounter(currentProgramCounter);
            cpu.loadInstructionRegister(instruction);
            if (pcb.state() == ProcessState.READY) {
                pcb.changeState(ProcessState.RUNNING);
            }
            progress.begin(memory, cpu, pcb, instruction, allocation);
        }

        if (!progress.consumeTick()) return TickResult.IN_PROGRESS;
        SemanticOutcome outcome;
        try {
            outcome = executeInstruction(progress.instruction().orElseThrow(), cpu, pcb, currentProgramCounter, screen, keyboard, filesystem);
        } catch (RuntimeException | Error failure) {
            progress.clear();
            throw failure;
        }

        return switch (outcome) {
            case Continue next -> publishNextPc(cpu, pcb, progress, next.nextLogicalPc());
            case Terminate ignored -> {
                progress.clear();
                pcb.changeState(ProcessState.TERMINATED);
                yield TickResult.PROGRAM_FINISHED;
            }
            case WaitForKeyboard ignored -> {
                progress.clear();
                pcb.changeState(ProcessState.BLOCKED);
                yield TickResult.WAITING_FOR_INPUT;
            }
        };
    }

    private TickResult publishNextPc(CpuRegisters<Instruction> cpu, ProcessControlBlock pcb,
            ExecutionProgress progress, int nextProgramCounter) {
        cpu.setProgramCounter(nextProgramCounter);
        pcb.setProgramCounter(nextProgramCounter);
        progress.clear();
        if (nextProgramCounter == pcb.instructionCount()) {
            pcb.changeState(ProcessState.TERMINATED);
            return TickResult.PROGRAM_FINISHED;
        }
        return TickResult.INSTRUCTION_COMPLETED;
    }

    // Permite ejecutar únicamente procesos READY o RUNNING.
    private void validateExecutableState(ProcessControlBlock pcb) {
        if (pcb.state() != ProcessState.READY && pcb.state() != ProcessState.RUNNING) {
            throw new ExecutionEngineException(
                    "Process state is not executable: " + pcb.state()
            );
        }
    }

    // Aplica la semántica de la instrucción y traduce fallos de rango de la CPU.
    private SemanticOutcome executeInstruction(
            Instruction instruction,
            CpuRegisters<Instruction> cpu,
            ProcessControlBlock pcb,
            int currentPc,
            ScreenDevice screen,
            KeyboardDevice keyboard,
            SimulatedFileSystem filesystem
    ) {
        try {
            switch (instruction) {
                case InterruptInstruction interrupt -> {
                    switch (interrupt.vector()) {
                        case FILESYSTEM -> filesystem.execute(cpu, pcb);
                        case TERMINATE -> { return new Terminate(); }
                        case SCREEN -> screen.append(cpu.readRegister(RegisterName.DX));
                        case KEYBOARD -> {
                            var input = keyboard.poll();
                            if (input.isEmpty()) return new WaitForKeyboard();
                            cpu.writeRegister(RegisterName.DX, input.getAsInt());
                        }
                    }
                }
                case CmpInstruction cmp -> cpu.writeConditionFlags(new ConditionFlags(
                        cpu.readRegister(cmp.left()) == cpu.readRegister(cmp.right()),
                        cpu.conditionFlags().overflow()));
                case JmpInstruction jump -> { return new Continue(branchTarget(currentPc, jump.displacement(), pcb.instructionCount())); }
                case JeInstruction jump -> {
                    if (cpu.conditionFlags().equal()) return new Continue(branchTarget(currentPc, jump.displacement(), pcb.instructionCount()));
                }
                case JneInstruction jump -> {
                    if (!cpu.conditionFlags().equal()) return new Continue(branchTarget(currentPc, jump.displacement(), pcb.instructionCount()));
                }
                case PushInstruction push -> pcb.stack().push(cpu.readRegister(push.source()));
                case PopInstruction pop -> cpu.writeRegister(pop.destination(), pcb.stack().pop());
                case ParamInstruction param -> pcb.stack().pushAll(
                        param.values().reversed().stream().map(ImmediateOperand::value).toList());
                case MovInstruction mov -> {
                    if (mov.source() instanceof TextOperand text) {
                        var value = new TextRegisterValue(text.value());
                        if (mov.destination() == RegisterName.DX) cpu.writeDx(value);
                        else cpu.writeAl(value); // Model restricts text destinations to DX or AL.
                    } else {
                        int value = switch (mov.source()) {
                            case ImmediateOperand immediate -> immediate.value();
                            case RegisterOperand register -> cpu.readRegister(register.register());
                            default -> throw new ExecutionEngineException("Invalid MOV source");
                        };
                        switch (mov.destination()) {
                            case RegisterName register -> cpu.writeRegister(register, value);
                            case ServiceRegister ignored -> cpu.writeAh(value); // Only AH accepts numeric service MOV.
                        }
                    }
                }
                case IncInstruction inc -> {
                    if (inc.target().isPresent()) {
                        var target = inc.target().orElseThrow();
                        cpu.writeRegister(target, cpu.readRegister(target) + 1);
                    } else cpu.writeAccumulator(cpu.accumulator() + 1);
                }
                case DecInstruction dec -> {
                    if (dec.target().isPresent()) {
                        var target = dec.target().orElseThrow();
                        cpu.writeRegister(target, cpu.readRegister(target) - 1);
                    } else cpu.writeAccumulator(cpu.accumulator() - 1);
                }
                case SwapInstruction swap -> {
                    int left = cpu.readRegister(swap.left());
                    int right = cpu.readRegister(swap.right());
                    cpu.writeRegister(swap.left(), right);
                    cpu.writeRegister(swap.right(), left);
                }
                case LoadInstruction load ->
                        cpu.writeAccumulator(cpu.readRegister(load.source()));
                case StoreInstruction store ->
                        cpu.writeRegister(store.destination(), cpu.accumulator());
                case AddInstruction add ->
                        cpu.writeAccumulator(
                                cpu.accumulator() + cpu.readRegister(add.source())
                        );
                case SubInstruction sub ->
                        cpu.writeAccumulator(
                                cpu.accumulator() - cpu.readRegister(sub.source())
                        );
            }
        } catch (FileSystemException exception) {
            throw new ExecutionEngineException("Filesystem instruction failed", exception);
        } catch (ProcessStackOverflowException | ProcessStackUnderflowException exception) {
            throw new ExecutionEngineException("Process stack operation failed", exception);
        } catch (InvalidRegisterValueException exception) {
            throw new ExecutionEngineException(
                    "Instruction result violates the CPU register range",
                    exception
            );
        }
        return new Continue(currentPc + 1);
    }

    private int branchTarget(int currentPc, BranchDisplacement displacement, int instructionCount) {
        long target = (long) currentPc + 1L + displacement.value();
        if (target < 0 || target >= instructionCount) {
            throw new ExecutionEngineException("Branch target outside process instructions: " + target);
        }
        return (int) target;
    }
}
