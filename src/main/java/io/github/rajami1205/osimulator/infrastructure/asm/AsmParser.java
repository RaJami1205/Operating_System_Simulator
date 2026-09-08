package io.github.rajami1205.osimulator.infrastructure.asm;

import io.github.rajami1205.osimulator.infrastructure.asm.exception.AsmParseException;
import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.AddInstruction;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.instruction.LoadInstruction;
import io.github.rajami1205.osimulator.model.instruction.StoreInstruction;
import io.github.rajami1205.osimulator.model.instruction.SubInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Converts textual ASM source lines into semantic instructions.
 */
public final class AsmParser {

    public List<Instruction> parse(List<String> sourceLines) {
        Objects.requireNonNull(sourceLines, "sourceLines must not be null");
        List<Instruction> instructions = new ArrayList<>();

        for (int index = 0; index < sourceLines.size(); index++) {
            String sourceLine = Objects.requireNonNull(
                    sourceLines.get(index),
                    "source line must not be null"
            );

            if (sourceLine.isBlank()) {
                continue;
            }

            instructions.add(parseLine(sourceLine, index + 1));
        }

        return List.copyOf(instructions);
    }

    private Instruction parseLine(String sourceLine, int lineNumber) {
        String[] tokens = sourceLine.strip().split("\\s+");
        String mnemonic = tokens[0].toUpperCase(Locale.ROOT);

        return switch (mnemonic) {
            case "LOAD" -> new LoadInstruction(
                    parseSingleRegisterOperand(tokens, mnemonic, lineNumber)
            );
            case "STORE" -> new StoreInstruction(
                    parseSingleRegisterOperand(tokens, mnemonic, lineNumber)
            );
            case "ADD" -> new AddInstruction(
                    parseSingleRegisterOperand(tokens, mnemonic, lineNumber)
            );
            case "SUB" -> new SubInstruction(
                    parseSingleRegisterOperand(tokens, mnemonic, lineNumber)
            );
            default -> throw new AsmParseException(
                    lineNumber,
                    "Unknown mnemonic '" + tokens[0] + "'"
            );
        };
    }

    private RegisterName parseSingleRegisterOperand(
            String[] tokens,
            String mnemonic,
            int lineNumber
    ) {
        if (tokens.length != 2) {
            throw new AsmParseException(
                    lineNumber,
                    mnemonic + " requires exactly one register operand"
            );
        }

        String registerToken = tokens[1];

        try {
            return RegisterName.valueOf(registerToken.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new AsmParseException(
                    lineNumber,
                    "Invalid register '" + registerToken + "'",
                    exception
            );
        }
    }
}
