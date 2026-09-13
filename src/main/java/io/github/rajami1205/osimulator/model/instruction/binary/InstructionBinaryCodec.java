package io.github.rajami1205.osimulator.model.instruction.binary;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.AddInstruction;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.instruction.LoadInstruction;
import io.github.rajami1205.osimulator.model.instruction.MovInstruction;
import io.github.rajami1205.osimulator.model.instruction.StoreInstruction;
import io.github.rajami1205.osimulator.model.instruction.SubInstruction;
import io.github.rajami1205.osimulator.model.instruction.binary.exception.InstructionBinaryCodecException;
import java.util.List;
import java.util.Objects;

/**
 * Convierte instrucciones semánticas al formato estricto Binary Format v1 y viceversa.
 */
public final class InstructionBinaryCodec {

    private static final int WORD_WIDTH = 8;
    private static final int MOV_WORD_COUNT = 2;
    private static final int REGISTER_ONLY_WORD_COUNT = 1;

    private static final String MOV_OPCODE = "000";
    private static final String LOAD_OPCODE = "001";
    private static final String STORE_OPCODE = "010";
    private static final String ADD_OPCODE = "011";
    private static final String SUB_OPCODE = "100";
    private static final String RESERVED_BITS = "000";

    // Crea el conversor de instrucciones para Binary Format v1.
    public InstructionBinaryCodec() {
    }

    // Convierte una instrucción semántica a Binary Format v1.
    public EncodedInstruction encode(Instruction instruction) {
        Objects.requireNonNull(instruction, "instruction must not be null");

        return switch (instruction) {
            case MovInstruction mov -> encodeMov(mov);
            case LoadInstruction load -> encodeRegisterOnly(LOAD_OPCODE, load.source());
            case StoreInstruction store -> encodeRegisterOnly(STORE_OPCODE, store.destination());
            case AddInstruction add -> encodeRegisterOnly(ADD_OPCODE, add.source());
            case SubInstruction sub -> encodeRegisterOnly(SUB_OPCODE, sub.source());
        };
    }

    // Valida la representación binaria y reconstruye su instrucción semántica.
    public Instruction decode(EncodedInstruction encoded) {
        Objects.requireNonNull(encoded, "encoded must not be null");

        List<BinaryWord> words = encoded.words();
        validateWordWidths(words);

        String header = words.get(0).bits();
        String reservedBits = header.substring(5);
        if (!RESERVED_BITS.equals(reservedBits)) {
            throw new InstructionBinaryCodecException(
                    "Header reserved bits must be 000: " + reservedBits
            );
        }

        String opcodeBits = header.substring(0, 3);
        RegisterName register = decodeRegister(header.substring(3, 5));

        return switch (opcodeBits) {
            case MOV_OPCODE -> {
                validateWordCount(words, MOV_WORD_COUNT, "MOV");
                yield decodeMov(register, words.get(1));
            }
            case LOAD_OPCODE -> {
                validateWordCount(words, REGISTER_ONLY_WORD_COUNT, "LOAD");
                yield new LoadInstruction(register);
            }
            case STORE_OPCODE -> {
                validateWordCount(words, REGISTER_ONLY_WORD_COUNT, "STORE");
                yield new StoreInstruction(register);
            }
            case ADD_OPCODE -> {
                validateWordCount(words, REGISTER_ONLY_WORD_COUNT, "ADD");
                yield new AddInstruction(register);
            }
            case SUB_OPCODE -> {
                validateWordCount(words, REGISTER_ONLY_WORD_COUNT, "SUB");
                yield new SubInstruction(register);
            }
            default -> throw new InstructionBinaryCodecException(
                    "Reserved or unknown opcode: " + opcodeBits
            );
        };
    }

    // Genera la cabecera y la palabra inmediata que representan MOV.
    private static EncodedInstruction encodeMov(MovInstruction instruction) {
        BinaryWord header = encodeHeader(MOV_OPCODE, instruction.destination());
        BinaryWord immediate = encodeImmediate(instruction.immediate());
        return new EncodedInstruction(List.of(header, immediate));
    }

    // Genera una única palabra para una operación con registro.
    private static EncodedInstruction encodeRegisterOnly(
            String opcodeBits,
            RegisterName register
    ) {
        return new EncodedInstruction(List.of(encodeHeader(opcodeBits, register)));
    }

    // Combina operación, registro y bits reservados en la cabecera.
    private static BinaryWord encodeHeader(String opcodeBits, RegisterName register) {
        return new BinaryWord(opcodeBits + encodeRegister(register) + RESERVED_BITS);
    }

    // Representa el inmediato mediante signo y magnitud en ocho bits.
    private static BinaryWord encodeImmediate(int immediate) {
        int magnitude = Math.abs(immediate);
        String magnitudeBits = toFixedWidthBinary(magnitude, WORD_WIDTH - 1);
        String signBit = immediate < 0 ? "1" : "0";
        return new BinaryWord(signBit + magnitudeBits);
    }

    // Completa con ceros la representación binaria hasta el ancho requerido.
    private static String toFixedWidthBinary(int value, int width) {
        String binary = Integer.toBinaryString(value);
        return "0".repeat(width - binary.length()) + binary;
    }

    // Asigna a cada registro su identificador binario de dos bits.
    private static String encodeRegister(RegisterName register) {
        return switch (register) {
            case AX -> "00";
            case BX -> "01";
            case CX -> "10";
            case DX -> "11";
        };
    }

    // Exige palabras de ocho bits para Binary Format v1.
    private static void validateWordWidths(List<BinaryWord> words) {
        for (int index = 0; index < words.size(); index++) {
            BinaryWord word = words.get(index);
            if (word.width() != WORD_WIDTH) {
                throw new InstructionBinaryCodecException(
                        "Word at index "
                                + index
                                + " must contain exactly "
                                + WORD_WIDTH
                                + " bits but contained "
                                + word.width()
                );
            }
        }
    }

    // Comprueba la cantidad de palabras requerida por la operación.
    private static void validateWordCount(
            List<BinaryWord> words,
            int expectedCount,
            String opcode
    ) {
        if (words.size() != expectedCount) {
            throw new InstructionBinaryCodecException(
                    opcode
                            + " requires exactly "
                            + expectedCount
                            + " word(s) but received "
                            + words.size()
            );
        }
    }

    // Resuelve el registro representado por sus dos bits.
    private static RegisterName decodeRegister(String registerBits) {
        return switch (registerBits) {
            case "00" -> RegisterName.AX;
            case "01" -> RegisterName.BX;
            case "10" -> RegisterName.CX;
            case "11" -> RegisterName.DX;
            default -> throw new InstructionBinaryCodecException(
                    "Unknown register bits: " + registerBits
            );
        };
    }

    // Reconstruye MOV a partir del inmediato y rechaza el cero negativo.
    private static MovInstruction decodeMov(RegisterName register, BinaryWord immediateWord) {
        String bits = immediateWord.bits();
        boolean negative = bits.charAt(0) == '1';
        int magnitude = Integer.parseInt(bits.substring(1), 2);

        if (negative && magnitude == 0) {
            throw new InstructionBinaryCodecException(
                    "MOV immediate must not use the negative-zero representation"
            );
        }

        int immediate = negative ? -magnitude : magnitude;
        return new MovInstruction(register, immediate);
    }
}
