package io.github.rajami1205.osimulator.infrastructure.asm;

import io.github.rajami1205.osimulator.infrastructure.asm.exception.AsmParseException;
import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.AddInstruction;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.instruction.LoadInstruction;
import io.github.rajami1205.osimulator.model.instruction.MovInstruction;
import io.github.rajami1205.osimulator.model.instruction.StoreInstruction;
import io.github.rajami1205.osimulator.model.instruction.SubInstruction;
import io.github.rajami1205.osimulator.model.instruction.IncInstruction;
import io.github.rajami1205.osimulator.model.instruction.DecInstruction;
import io.github.rajami1205.osimulator.model.instruction.SwapInstruction;
import io.github.rajami1205.osimulator.model.instruction.exception.InvalidImmediateValueException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convierte líneas de texto ASM en instrucciones semánticas.
 */
public final class AsmParser {

    private static final Pattern TWO_OPERAND_SYNTAX = Pattern.compile(
            "\\S+\\s+([^\\s,]+)\\s*,\\s*([^\\s,]+)"
    );
    private static final Pattern DECIMAL_INTEGER = Pattern.compile("[+-]?[0-9]+");

    // Convierte las líneas no vacías en una lista inmutable de instrucciones.
    public List<Instruction> parse(List<String> sourceLines) {
        Objects.requireNonNull(sourceLines, "sourceLines must not be null");
        List<Instruction> instructions = new ArrayList<>();

        for (int index = 0; index < sourceLines.size(); index++) {
            String sourceLine = Objects.requireNonNull(
                    sourceLines.get(index),
                    "source line must not be null"
            );

            int comment = sourceLine.indexOf(';');
            sourceLine = (comment >= 0 ? sourceLine.substring(0, comment) : sourceLine).strip();
            if (sourceLine.isEmpty()) {
                continue;
            }

            instructions.add(parseLine(sourceLine, index + 1));
        }

        return List.copyOf(instructions);
    }

    // Identifica la operación ASM y delega la validación de sus operandos.
    private Instruction parseLine(String sourceLine, int lineNumber) {
        String normalizedLine = sourceLine.strip();
        String[] tokens = normalizedLine.split("\\s+");
        String mnemonic = tokens[0].toUpperCase(Locale.ROOT);

        return switch (mnemonic) {
            case "MOV" -> parseMovInstruction(normalizedLine, lineNumber);
            case "INC" -> tokens.length == 1 ? new IncInstruction()
                    : new IncInstruction(parseSingleRegisterOperand(tokens, mnemonic, lineNumber));
            case "DEC" -> tokens.length == 1 ? new DecInstruction()
                    : new DecInstruction(parseSingleRegisterOperand(tokens, mnemonic, lineNumber));
            case "SWAP" -> {
                Matcher operands = parseTwoOperands(normalizedLine, mnemonic, lineNumber);
                yield new SwapInstruction(parseRegister(operands.group(1), lineNumber),
                        parseRegister(operands.group(2), lineNumber));
            }
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

    // Exige un único registro como operando de la instrucción.
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

        return parseRegister(tokens[1], lineNumber);
    }

    private Matcher parseTwoOperands(String sourceLine, String mnemonic, int lineNumber) {
        Matcher matcher = TWO_OPERAND_SYNTAX.matcher(sourceLine);
        if (!matcher.matches()) {
            throw new AsmParseException(
                    lineNumber,
                    mnemonic + " requires exactly two comma-separated operands"
            );
        }
        return matcher;
    }

    // MOV comparte estructura con SWAP, pero admite registro o inmediato como origen.
    private Instruction parseMovInstruction(String sourceLine, int lineNumber) {
        Matcher matcher = parseTwoOperands(sourceLine, "MOV", lineNumber);
        RegisterName destination = parseRegister(matcher.group(1), lineNumber);
        if (!DECIMAL_INTEGER.matcher(matcher.group(2)).matches()) {
            return new MovInstruction(destination, parseRegister(matcher.group(2), lineNumber));
        }
        int immediate = parseImmediate(matcher.group(2), lineNumber);

        try {
            return new MovInstruction(destination, immediate);
        } catch (InvalidImmediateValueException exception) {
            throw new AsmParseException(
                    lineNumber,
                    "Invalid MOV immediate '" + matcher.group(2) + "'",
                    exception
            );
        }
    }

    // Interpreta un entero decimal y conserva la línea de origen en los errores.
    private int parseImmediate(String immediateToken, int lineNumber) {
        if (!DECIMAL_INTEGER.matcher(immediateToken).matches()) {
            throw new AsmParseException(
                    lineNumber,
                    "Invalid decimal immediate '" + immediateToken + "'"
            );
        }

        try {
            return Integer.parseInt(immediateToken);
        } catch (NumberFormatException exception) {
            throw new AsmParseException(
                    lineNumber,
                    "Invalid decimal immediate '" + immediateToken + "'",
                    exception
            );
        }
    }

    // Resuelve el registro sin distinguir mayúsculas y reporta nombres inválidos.
    private RegisterName parseRegister(String registerToken, int lineNumber) {

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
