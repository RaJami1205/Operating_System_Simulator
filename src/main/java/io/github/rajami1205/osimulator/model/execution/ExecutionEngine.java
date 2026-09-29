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
import io.github.rajami1205.osimulator.model.memory.exception.MemoryProtectionException;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import java.util.Objects;
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

    // Consume un tick; los efectos semánticos se aplican únicamente en el tick final.
    public TickResult executeTick(
            MainMemory memory,
            CpuRegisters<Instruction> cpu,
            ProcessControlBlock pcb,
            ExecutionProgress progress
    ) {
        Objects.requireNonNull(memory, "memory must not be null");
        Objects.requireNonNull(cpu, "cpu must not be null");
        Objects.requireNonNull(pcb, "pcb must not be null");
        Objects.requireNonNull(progress, "progress must not be null");

        validateExecutableState(pcb);
        progress.validateContext(memory, cpu, pcb);

        int currentProgramCounter = pcb.programCounter();
        int instructionCount = pcb.instructionCount();
        if (currentProgramCounter == instructionCount) {
            cpu.setProgramCounter(instructionCount);
            pcb.changeState(ProcessState.TERMINATED);
            return TickResult.PROGRAM_FINISHED;
        }

        if (progress.isIdle()) {
            Instruction instruction;
            try {
                instruction = memory.readInstruction(pcb.memoryBounds(), currentProgramCounter);
            } catch (MemoryProtectionException exception) {
                throw new ExecutionEngineException("Unable to fetch process instruction", exception);
            }

            cpu.setProgramCounter(currentProgramCounter);
            cpu.loadInstructionRegister(instruction);
            if (pcb.state() == ProcessState.READY) {
                pcb.changeState(ProcessState.RUNNING);
            }
            progress.begin(memory, cpu, pcb, instruction);
        }

        if (!progress.consumeTick()) return TickResult.IN_PROGRESS;
        int nextProgramCounter;
        try {
            nextProgramCounter = executeInstruction(progress.instruction().orElseThrow(), cpu, pcb, currentProgramCounter);
        } catch (RuntimeException | Error failure) {
            progress.clear();
            throw failure;
        }

        cpu.setProgramCounter(nextProgramCounter);
        pcb.setProgramCounter(nextProgramCounter);
        progress.clear();
        if (nextProgramCounter == instructionCount) {
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
    private int executeInstruction(
            Instruction instruction,
            CpuRegisters<Instruction> cpu,
            ProcessControlBlock pcb,
            int currentPc
    ) {
        try {
            switch (instruction) {
                case CmpInstruction cmp -> cpu.writeConditionFlags(new ConditionFlags(
                        cpu.readRegister(cmp.left()) == cpu.readRegister(cmp.right()),
                        cpu.conditionFlags().overflow()));
                case JmpInstruction jump -> { return branchTarget(currentPc, jump.displacement(), pcb.instructionCount()); }
                case JeInstruction jump -> {
                    if (cpu.conditionFlags().equal()) return branchTarget(currentPc, jump.displacement(), pcb.instructionCount());
                }
                case JneInstruction jump -> {
                    if (!cpu.conditionFlags().equal()) return branchTarget(currentPc, jump.displacement(), pcb.instructionCount());
                }
                case PushInstruction push -> pcb.stack().push(cpu.readRegister(push.source()));
                case PopInstruction pop -> cpu.writeRegister(pop.destination(), pcb.stack().pop());
                case ParamInstruction param -> pcb.stack().pushAll(
                        param.values().reversed().stream().map(ImmediateOperand::value).toList());
                case MovInstruction mov ->
                        cpu.writeRegister(mov.destination(), switch (mov.source()) {
                            case ImmediateOperand immediate -> immediate.value();
                            case RegisterOperand register -> cpu.readRegister(register.register());
                            case BranchDisplacement ignored -> throw new ExecutionEngineException("Invalid MOV source");
                        });
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
        } catch (ProcessStackOverflowException | ProcessStackUnderflowException exception) {
            throw new ExecutionEngineException("Process stack operation failed", exception);
        } catch (InvalidRegisterValueException exception) {
            throw new ExecutionEngineException(
                    "Instruction result violates the CPU register range",
                    exception
            );
        }
        return currentPc + 1;
    }

    private int branchTarget(int currentPc, BranchDisplacement displacement, int instructionCount) {
        long target = (long) currentPc + 1L + displacement.value();
        if (target < 0 || target >= instructionCount) {
            throw new ExecutionEngineException("Branch target outside process instructions: " + target);
        }
        return (int) target;
    }
}
