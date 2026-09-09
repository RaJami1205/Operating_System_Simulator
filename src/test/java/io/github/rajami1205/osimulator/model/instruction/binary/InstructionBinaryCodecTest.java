package io.github.rajami1205.osimulator.model.instruction.binary;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.AddInstruction;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.instruction.LoadInstruction;
import io.github.rajami1205.osimulator.model.instruction.MovInstruction;
import io.github.rajami1205.osimulator.model.instruction.StoreInstruction;
import io.github.rajami1205.osimulator.model.instruction.SubInstruction;
import io.github.rajami1205.osimulator.model.instruction.binary.exception.InstructionBinaryCodecException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class InstructionBinaryCodecTest {

    private final InstructionBinaryCodec codec = new InstructionBinaryCodec();

    @ParameterizedTest
    @MethodSource("registerOnlyInstructions")
    void shouldEncodeAndDecodeRegisterOnlyInstructionsWithExactMappings(
            Instruction instruction,
            String expectedBits
    ) {
        EncodedInstruction encoded = codec.encode(instruction);

        assertAll(
                () -> assertEquals(1, encoded.words().size()),
                () -> assertEquals(expectedBits, encoded.words().get(0).bits()),
                () -> assertEquals(instruction, codec.decode(encoded))
        );
    }

    @ParameterizedTest
    @MethodSource("movRegisterMappings")
    void shouldEncodeAndDecodeMovForEveryRegister(
            RegisterName destination,
            String expectedHeader
    ) {
        MovInstruction instruction = new MovInstruction(destination, 5);
        EncodedInstruction encoded = codec.encode(instruction);

        assertAll(
                () -> assertEquals(2, encoded.words().size()),
                () -> assertEquals(expectedHeader, encoded.words().get(0).bits()),
                () -> assertEquals("00000101", encoded.words().get(1).bits()),
                () -> assertEquals(instruction, codec.decode(encoded))
        );
    }

    @ParameterizedTest
    @MethodSource("signedImmediateMappings")
    void shouldEncodeAndDecodeMovSignMagnitude(int immediate, String expectedBits) {
        MovInstruction instruction = new MovInstruction(RegisterName.AX, immediate);
        EncodedInstruction encoded = codec.encode(instruction);

        assertAll(
                () -> assertEquals("00000000", encoded.words().get(0).bits()),
                () -> assertEquals(expectedBits, encoded.words().get(1).bits()),
                () -> assertEquals(instruction, codec.decode(encoded))
        );
    }

    @ParameterizedTest
    @MethodSource("requiredMovExamples")
    void shouldProduceRequiredExactMovExamples(
            MovInstruction instruction,
            String expectedHeader,
            String expectedImmediate
    ) {
        EncodedInstruction encoded = codec.encode(instruction);

        assertEquals(
                List.of(
                        new BinaryWord(expectedHeader),
                        new BinaryWord(expectedImmediate)
                ),
                encoded.words()
        );
    }

    @Test
    void shouldRejectNullInstruction() {
        assertThrows(NullPointerException.class, () -> codec.encode(null));
    }

    @Test
    void shouldRejectNullEncodedInstruction() {
        assertThrows(NullPointerException.class, () -> codec.decode(null));
    }

    @ParameterizedTest
    @MethodSource("wrongWidthEncodings")
    void shouldRejectWordsWithWrongWidth(EncodedInstruction encoded) {
        assertCodecFailure(encoded);
    }

    @ParameterizedTest
    @MethodSource("wrongWordCountEncodings")
    void shouldRejectWrongWordCounts(EncodedInstruction encoded) {
        assertCodecFailure(encoded);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10100000", "11000000", "11100000"})
    void shouldRejectReservedOpcodes(String header) {
        assertCodecFailure(encoded(header));
    }

    @ParameterizedTest
    @ValueSource(strings = {"00100001", "00100010", "00100111"})
    void shouldRejectNonZeroReservedHeaderBits(String header) {
        assertCodecFailure(encoded(header));
    }

    @Test
    void shouldRejectNegativeZero() {
        assertCodecFailure(encoded("00000000", "10000000"));
    }

    @ParameterizedTest
    @MethodSource("canonicalEncodings")
    void shouldPreserveCanonicalBinaryRoundTrip(EncodedInstruction encoded) {
        assertEquals(encoded, codec.encode(codec.decode(encoded)));
    }

    @Test
    void shouldRemainDeterministicAndReusable() {
        Instruction first = new MovInstruction(RegisterName.BX, -5);
        Instruction second = new AddInstruction(RegisterName.CX);

        EncodedInstruction firstEncoding = codec.encode(first);
        EncodedInstruction secondEncoding = codec.encode(second);

        assertAll(
                () -> assertEquals(firstEncoding, codec.encode(first)),
                () -> assertEquals(secondEncoding, codec.encode(second)),
                () -> assertEquals(first, codec.decode(firstEncoding)),
                () -> assertEquals(second, codec.decode(secondEncoding))
        );
    }

    @Test
    void shouldSupportApprovedCodecExceptionConstructors() {
        RuntimeException cause = new RuntimeException("cause");
        InstructionBinaryCodecException withMessage =
                new InstructionBinaryCodecException("message");
        InstructionBinaryCodecException withCause =
                new InstructionBinaryCodecException("message", cause);

        assertAll(
                () -> assertEquals("message", withMessage.getMessage()),
                () -> assertEquals("message", withCause.getMessage()),
                () -> assertEquals(cause, withCause.getCause())
        );
    }

    private void assertCodecFailure(EncodedInstruction encoded) {
        InstructionBinaryCodecException exception = assertThrows(
                InstructionBinaryCodecException.class,
                () -> codec.decode(encoded)
        );

        assertAll(
                () -> assertNotNull(exception.getMessage()),
                () -> assertFalse(exception.getMessage().isBlank())
        );
    }

    private static EncodedInstruction encoded(String... words) {
        return new EncodedInstruction(
                Stream.of(words)
                        .map(BinaryWord::new)
                        .toList()
        );
    }

    private static Stream<Arguments> registerOnlyInstructions() {
        return Stream.of(
                Arguments.of(new LoadInstruction(RegisterName.AX), "00100000"),
                Arguments.of(new LoadInstruction(RegisterName.BX), "00101000"),
                Arguments.of(new LoadInstruction(RegisterName.CX), "00110000"),
                Arguments.of(new LoadInstruction(RegisterName.DX), "00111000"),
                Arguments.of(new StoreInstruction(RegisterName.AX), "01000000"),
                Arguments.of(new StoreInstruction(RegisterName.BX), "01001000"),
                Arguments.of(new StoreInstruction(RegisterName.CX), "01010000"),
                Arguments.of(new StoreInstruction(RegisterName.DX), "01011000"),
                Arguments.of(new AddInstruction(RegisterName.AX), "01100000"),
                Arguments.of(new AddInstruction(RegisterName.BX), "01101000"),
                Arguments.of(new AddInstruction(RegisterName.CX), "01110000"),
                Arguments.of(new AddInstruction(RegisterName.DX), "01111000"),
                Arguments.of(new SubInstruction(RegisterName.AX), "10000000"),
                Arguments.of(new SubInstruction(RegisterName.BX), "10001000"),
                Arguments.of(new SubInstruction(RegisterName.CX), "10010000"),
                Arguments.of(new SubInstruction(RegisterName.DX), "10011000")
        );
    }

    private static Stream<Arguments> movRegisterMappings() {
        return Stream.of(
                Arguments.of(RegisterName.AX, "00000000"),
                Arguments.of(RegisterName.BX, "00001000"),
                Arguments.of(RegisterName.CX, "00010000"),
                Arguments.of(RegisterName.DX, "00011000")
        );
    }

    private static Stream<Arguments> signedImmediateMappings() {
        return Stream.of(
                Arguments.of(0, "00000000"),
                Arguments.of(1, "00000001"),
                Arguments.of(-1, "10000001"),
                Arguments.of(5, "00000101"),
                Arguments.of(-5, "10000101"),
                Arguments.of(127, "01111111"),
                Arguments.of(-127, "11111111")
        );
    }

    private static Stream<Arguments> requiredMovExamples() {
        return Stream.of(
                Arguments.of(
                        new MovInstruction(RegisterName.AX, 5),
                        "00000000",
                        "00000101"
                ),
                Arguments.of(
                        new MovInstruction(RegisterName.BX, -5),
                        "00001000",
                        "10000101"
                )
        );
    }

    private static Stream<Arguments> wrongWidthEncodings() {
        return Stream.of(
                Arguments.of(encoded("0010000")),
                Arguments.of(encoded("001000000")),
                Arguments.of(encoded("00000000", "0000101"))
        );
    }

    private static Stream<Arguments> wrongWordCountEncodings() {
        return Stream.of(
                Arguments.of(encoded("00000000")),
                Arguments.of(encoded("00000000", "00000101", "00000000")),
                Arguments.of(encoded("00100000", "00000000")),
                Arguments.of(encoded("01000000", "00000000")),
                Arguments.of(encoded("01100000", "00000000")),
                Arguments.of(encoded("10000000", "00000000"))
        );
    }

    private static Stream<Arguments> canonicalEncodings() {
        return Stream.of(
                Arguments.of(encoded("00000000", "00000101")),
                Arguments.of(encoded("00001000", "10000101")),
                Arguments.of(encoded("00100000")),
                Arguments.of(encoded("01001000")),
                Arguments.of(encoded("01110000")),
                Arguments.of(encoded("10011000"))
        );
    }
}
