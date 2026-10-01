package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.simulator.SimulatorSnapshot.InstructionSnapshot;
import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.AddInstruction;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.instruction.CmpInstruction;
import io.github.rajami1205.osimulator.model.instruction.JmpInstruction;
import io.github.rajami1205.osimulator.model.instruction.JeInstruction;
import io.github.rajami1205.osimulator.model.instruction.JneInstruction;
import io.github.rajami1205.osimulator.model.instruction.ParamInstruction;
import io.github.rajami1205.osimulator.model.instruction.PushInstruction;
import io.github.rajami1205.osimulator.model.instruction.PopInstruction;
import io.github.rajami1205.osimulator.model.instruction.operand.BranchDisplacement;
import io.github.rajami1205.osimulator.model.instruction.LoadInstruction;
import io.github.rajami1205.osimulator.model.instruction.MovInstruction;
import io.github.rajami1205.osimulator.model.instruction.StoreInstruction;
import io.github.rajami1205.osimulator.model.instruction.SubInstruction;
import io.github.rajami1205.osimulator.model.instruction.IncInstruction;
import io.github.rajami1205.osimulator.model.instruction.DecInstruction;
import io.github.rajami1205.osimulator.model.instruction.SwapInstruction;
import io.github.rajami1205.osimulator.model.instruction.operand.ImmediateOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;
import io.github.rajami1205.osimulator.model.instruction.InterruptInstruction;
import io.github.rajami1205.osimulator.model.instruction.operand.InterruptVector;
import io.github.rajami1205.osimulator.model.filesystem.FileService;
import io.github.rajami1205.osimulator.model.cpu.ServiceRegister;
import io.github.rajami1205.osimulator.model.instruction.operand.TextOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.ServiceRegisterOperand;

/** Canonical ASM display shared by all Application read views. */
final class SemanticFormatter {
    private SemanticFormatter() {}
    // Obtiene la representación semántica de una instrucción.
    static InstructionSnapshot instructionSnapshot(Instruction instruction) {
        return new InstructionSnapshot(semanticText(instruction), instruction.opcode().name(),
                operandText(instruction));
    }

    // Compone el texto de una instrucción para su visualización.
    static String semanticText(Instruction instruction) {
        String operands = operandText(instruction);
        return instruction.opcode().name() + (operands.isEmpty() ? "" : " " + operands);
    }

    // Describe los operandos según el tipo de instrucción.
    private static String operandText(Instruction instruction) {
        return switch (instruction) {
            case MovInstruction mov -> mov.destination().name() + ", " + switch (mov.source()) {
                case ImmediateOperand immediate -> mov.destination() == ServiceRegister.AH
                        ? FileService.find(immediate.value()).map(FileService::canonicalText).orElse(Integer.toString(immediate.value()))
                        : Integer.toString(immediate.value());
                case TextOperand text -> "\"" + text.value() + "\"";
                case ServiceRegisterOperand ignored -> throw new IllegalStateException("Invalid MOV source");
                case RegisterOperand register -> register.register().name();
                case InterruptVector ignored -> throw new IllegalStateException("Invalid MOV source");
                case BranchDisplacement ignored -> throw new IllegalStateException("Invalid MOV source");
            };
            case InterruptInstruction interrupt -> interrupt.vector().canonicalText();
            case CmpInstruction cmp -> cmp.left().name() + ", " + cmp.right().name();
            case JmpInstruction jump -> displacementText(jump.displacement());
            case JeInstruction jump -> displacementText(jump.displacement());
            case JneInstruction jump -> displacementText(jump.displacement());
            case PushInstruction push -> push.source().name();
            case PopInstruction pop -> pop.destination().name();
            case ParamInstruction param -> param.values().stream().map(value -> Integer.toString(value.value()))
                    .collect(java.util.stream.Collectors.joining(", "));
            case IncInstruction inc -> inc.target().map(RegisterName::name).orElse("");
            case DecInstruction dec -> dec.target().map(RegisterName::name).orElse("");
            case SwapInstruction swap -> swap.left().name() + ", " + swap.right().name();
            case LoadInstruction load -> load.source().name();
            case StoreInstruction store -> store.destination().name();
            case AddInstruction add -> add.source().name();
            case SubInstruction sub -> sub.source().name();
        };
    }

    private static String displacementText(BranchDisplacement displacement) {
        return displacement.value() > 0 ? "+" + displacement.value() : Integer.toString(displacement.value());
    }

}
