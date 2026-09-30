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
import io.github.rajami1205.osimulator.model.instruction.CmpInstruction;
import io.github.rajami1205.osimulator.model.instruction.JmpInstruction;
import io.github.rajami1205.osimulator.model.instruction.JeInstruction;
import io.github.rajami1205.osimulator.model.instruction.JneInstruction;
import io.github.rajami1205.osimulator.model.instruction.ParamInstruction;
import io.github.rajami1205.osimulator.model.instruction.PushInstruction;
import io.github.rajami1205.osimulator.model.instruction.PopInstruction;
import io.github.rajami1205.osimulator.model.instruction.operand.BranchDisplacement;
import io.github.rajami1205.osimulator.model.instruction.operand.ImmediateOperand;
import io.github.rajami1205.osimulator.model.instruction.InterruptInstruction;
import io.github.rajami1205.osimulator.model.instruction.operand.InterruptVector;
import io.github.rajami1205.osimulator.model.cpu.MovDestination;
import io.github.rajami1205.osimulator.model.cpu.ServiceRegister;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.TextOperand;
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

            sourceLine = stripComment(sourceLine, index + 1).strip();
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
            case "INT" -> {
                if (tokens.length != 2) throw new AsmParseException(lineNumber, "INT requires exactly one vector");
                try {
                    yield new InterruptInstruction(InterruptVector.parse(tokens[1]));
                } catch (IllegalArgumentException exception) {
                    throw new AsmParseException(lineNumber, exception.getMessage(), exception);
                }
            }
            case "CMP" -> {
                Matcher operands = parseTwoOperands(normalizedLine, mnemonic, lineNumber);
                yield new CmpInstruction(parseRegister(operands.group(1), lineNumber),
                        parseRegister(operands.group(2), lineNumber));
            }
            case "JMP" -> new JmpInstruction(parseDisplacement(tokens, lineNumber));
            case "JE" -> new JeInstruction(parseDisplacement(tokens, lineNumber));
            case "JNE" -> new JneInstruction(parseDisplacement(tokens, lineNumber));
            case "PUSH" -> new PushInstruction(parseSingleRegisterOperand(tokens, mnemonic, lineNumber));
            case "POP" -> new PopInstruction(parseSingleRegisterOperand(tokens, mnemonic, lineNumber));
            case "PARAM" -> parseParameters(normalizedLine.substring(tokens[0].length()).strip(), lineNumber);
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

    private BranchDisplacement parseDisplacement(String[] tokens, int lineNumber) {
        if (tokens.length != 2) throw new AsmParseException(lineNumber, "Branch requires one displacement");
        return new BranchDisplacement(parseImmediate(tokens[1], lineNumber));
    }

    private ParamInstruction parseParameters(String text, int lineNumber) {
        String[] tokens = text.split(",", -1);
        if (tokens.length > ParamInstruction.MAX_VALUES) {
            throw new AsmParseException(lineNumber, "PARAM requires one to three values");
        }
        List<ImmediateOperand> values = new ArrayList<>();
        for (String token : tokens) {
            try {
                values.add(new ImmediateOperand(parseImmediate(token.strip(), lineNumber)));
            } catch (InvalidImmediateValueException exception) {
                throw new AsmParseException(lineNumber, "Invalid PARAM value '" + token.strip() + "'", exception);
            }
        }
        return new ParamInstruction(values);
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

    // No escapes: quotes delimit one literal; commas and semicolons inside it are data.
    private String stripComment(String line, int lineNumber) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char value = line.charAt(i);
            if (value == '\n' || value == '\r') throw new AsmParseException(lineNumber, "Line breaks are not valid inside a source line");
            if (value == '"') quoted = !quoted;
            if (value == ';' && !quoted) return line.substring(0, i);
        }
        if (quoted) throw new AsmParseException(lineNumber, "Unclosed string literal");
        return line;
    }

    private Instruction parseMovInstruction(String sourceLine, int lineNumber) {
        String operands = sourceLine.substring(3).strip();
        boolean quoted = false;
        int comma = -1;
        for (int i = 0; i < operands.length(); i++) {
            char value = operands.charAt(i);
            if (value == '"') quoted = !quoted;
            if (value == ',' && !quoted) {
                if (comma >= 0) throw new AsmParseException(lineNumber, "MOV requires two operands");
                comma = i;
            }
        }
        if (comma < 0) throw new AsmParseException(lineNumber, "MOV requires two comma-separated operands");
        String target = operands.substring(0, comma).strip().toUpperCase(Locale.ROOT);
        String source = operands.substring(comma + 1).strip();
        MovDestination destination = target.equals("AH") || target.equals("AL")
                ? ServiceRegister.valueOf(target) : parseRegister(target, lineNumber);
        try {
            InstructionOperand operand;
            if (source.startsWith("\"")) {
                if (source.length() < 2 || !source.endsWith("\"")
                        || source.substring(1, source.length() - 1).indexOf('"') >= 0) {
                    throw new AsmParseException(lineNumber, "Malformed string literal");
                }
                operand = new TextOperand(source.substring(1, source.length() - 1));
            } else if (source.equalsIgnoreCase("AH") || source.equalsIgnoreCase("AL")) {
                throw new AsmParseException(lineNumber, "Service registers cannot be MOV sources");
            } else if (DECIMAL_INTEGER.matcher(source).matches() || source.toUpperCase(Locale.ROOT).endsWith("H")) {
                operand = new ImmediateOperand(parseNumericLiteral(source, lineNumber));
            } else {
                operand = new RegisterOperand(parseRegister(source, lineNumber));
            }
            return new MovInstruction(destination, operand);
        } catch (AsmParseException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new AsmParseException(lineNumber, "Invalid MOV operands: " + exception.getMessage(), exception);
        }
    }

    private int parseNumericLiteral(String token, int lineNumber) {
        if (token.matches("[0-9A-Fa-f]+[Hh]")) {
            try { return Integer.parseInt(token.substring(0, token.length() - 1), 16); }
            catch (NumberFormatException exception) {
                throw new AsmParseException(lineNumber, "Hex literal outside integer range", exception);
            }
        }
        return parseImmediate(token, lineNumber);
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
