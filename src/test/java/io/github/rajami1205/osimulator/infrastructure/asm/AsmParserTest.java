package io.github.rajami1205.osimulator.infrastructure.asm;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rajami1205.osimulator.infrastructure.asm.exception.AsmParseException;
import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.AddInstruction;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.instruction.LoadInstruction;
import io.github.rajami1205.osimulator.model.instruction.MovInstruction;
import io.github.rajami1205.osimulator.model.instruction.StoreInstruction;
import io.github.rajami1205.osimulator.model.instruction.SubInstruction;
import io.github.rajami1205.osimulator.model.instruction.exception.InvalidImmediateValueException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class AsmParserTest {

    private final AsmParser parser = new AsmParser();

    @Test
    void shouldParseLoadInstruction() {
        List<Instruction> result = parser.parse(List.of("LOAD AX"));

        assertEquals(List.of(new LoadInstruction(RegisterName.AX)), result);
    }

    @Test
    void shouldParseStoreInstruction() {
        List<Instruction> result = parser.parse(List.of("STORE BX"));

        assertEquals(List.of(new StoreInstruction(RegisterName.BX)), result);
    }

    @Test
    void shouldParseAddInstruction() {
        List<Instruction> result = parser.parse(List.of("ADD CX"));

        assertEquals(List.of(new AddInstruction(RegisterName.CX)), result);
    }

    @Test
    void shouldParseSubInstruction() {
        List<Instruction> result = parser.parse(List.of("SUB DX"));

        assertEquals(List.of(new SubInstruction(RegisterName.DX)), result);
    }

    @Test
    void shouldParseMovInstruction() {
        List<Instruction> result = parser.parse(List.of("MOV AX, 25"));

        assertEquals(List.of(new MovInstruction(RegisterName.AX, 25)), result);
    }

    @Test
    void shouldParseMovCaseInsensitively() {
        List<Instruction> result = parser.parse(List.of("mOv aX, 25"));

        assertEquals(List.of(new MovInstruction(RegisterName.AX, 25)), result);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MOV AX,25",
            "MOV AX , 25",
            "MOV       AX       ,       25",
            "\tMOV\tAX\t,\t25\t"
    })
    void shouldParseMovWhitespaceVariations(String sourceLine) {
        List<Instruction> result = parser.parse(List.of(sourceLine));

        assertEquals(List.of(new MovInstruction(RegisterName.AX, 25)), result);
    }

    @Test
    void shouldTolerateVeryWideWhitespaceInMov() {
        String sourceLine = "mOv"
                + " ".repeat(1_000)
                + "BX"
                + " ".repeat(1_000)
                + ","
                + " ".repeat(1_000)
                + "-5";

        List<Instruction> result = parser.parse(List.of(sourceLine));

        assertEquals(List.of(new MovInstruction(RegisterName.BX, -5)), result);
    }

    @ParameterizedTest
    @EnumSource(RegisterName.class)
    void shouldParseEveryMovDestination(RegisterName destination) {
        List<Instruction> result = parser.parse(
                List.of("MOV " + destination.name() + ", 5")
        );

        assertEquals(List.of(new MovInstruction(destination, 5)), result);
    }

    @ParameterizedTest
    @ValueSource(ints = {-127, 127})
    void shouldParseValidMovImmediateBoundaries(int immediate) {
        List<Instruction> result = parser.parse(List.of("MOV AX, " + immediate));

        assertEquals(List.of(new MovInstruction(RegisterName.AX, immediate)), result);
    }

    @Test
    void shouldParseOptionalPlusSign() {
        List<Instruction> result = parser.parse(List.of("MOV AX, +25"));

        assertEquals(List.of(new MovInstruction(RegisterName.AX, 25)), result);
    }

    @ParameterizedTest
    @CsvSource({
            "0005, 5",
            "+0005, 5",
            "-0005, -5"
    })
    void shouldParseDecimalImmediateWithLeadingZeros(String immediateToken, int expected) {
        List<Instruction> result = parser.parse(List.of("MOV AX, " + immediateToken));

        assertEquals(List.of(new MovInstruction(RegisterName.AX, expected)), result);
    }

    @ParameterizedTest
    @ValueSource(ints = {-128, 128})
    void shouldWrapMovImmediateValuesRejectedByModel(int immediate) {
        AsmParseException exception = assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of("MOV AX, " + immediate))
        );

        assertAll(
                () -> assertEquals(1, exception.lineNumber()),
                () -> assertFalse(exception.getMessage().isBlank()),
                () -> assertInstanceOf(
                        InvalidImmediateValueException.class,
                        exception.getCause()
                )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "12.5", "0x10", "0b1010", "+", "1e2"})
    void shouldRejectMalformedMovImmediate(String immediateToken) {
        AsmParseException exception = assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of("MOV AX, " + immediateToken))
        );

        assertAll(
                () -> assertEquals(1, exception.lineNumber()),
                () -> assertFalse(exception.getMessage().isBlank())
        );
    }

    @Test
    void shouldWrapIntegerOverflowText() {
        AsmParseException exception = assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of("MOV AX, 999999999999999999999999"))
        );

        assertAll(
                () -> assertEquals(1, exception.lineNumber()),
                () -> assertInstanceOf(NumberFormatException.class, exception.getCause())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"MOV AX 25", "MOV AX                 25", "MOV AX"})
    void shouldRejectMovWithoutComma(String sourceLine) {
        assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of(sourceLine))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MOV AX,,25",
            "MOV AX,25,30",
            "MOV ,25",
            "MOV AX,",
            "MOV , 25",
            "MOV AX , , 25"
    })
    void shouldRejectInvalidMovCommaStructure(String sourceLine) {
        assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of(sourceLine))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"MOV AX, 25 extra", "MOV AX, 25 BX"})
    void shouldRejectExtraMovTokens(String sourceLine) {
        assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of(sourceLine))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MOV AX, 25 ; comment",
            "MOV AX, 25 # comment",
            "MOV AX, 25 // comment"
    })
    void shouldRejectCommentsAfterMov(String sourceLine) {
        assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of(sourceLine))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"MOV X1, 25", "MOV AC, 25"})
    void shouldRejectInvalidMovDestination(String sourceLine) {
        AsmParseException exception = assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of(sourceLine))
        );

        assertAll(
                () -> assertEquals(1, exception.lineNumber()),
                () -> assertFalse(exception.getMessage().isBlank())
        );
    }

    @Test
    void shouldPreservePhysicalLineNumberForInvalidMovAfterBlankLines() {
        AsmParseException exception = assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of(
                        "LOAD AX",
                        "",
                        "   ",
                        "ADD BX",
                        "",
                        "MOV CX, 128"
                ))
        );

        assertAll(
                () -> assertEquals(6, exception.lineNumber()),
                () -> assertInstanceOf(
                        InvalidImmediateValueException.class,
                        exception.getCause()
                )
        );
    }

    @Test
    void shouldPreserveMixedInstructionOrderAcrossBlankLines() {
        List<Instruction> result = parser.parse(List.of(
                "MOV AX, 10",
                "",
                "LOAD AX",
                "",
                "",
                "ADD BX",
                "MOV CX, -5",
                "",
                "STORE DX",
                "SUB AX"
        ));

        assertEquals(
                List.of(
                        new MovInstruction(RegisterName.AX, 10),
                        new LoadInstruction(RegisterName.AX),
                        new AddInstruction(RegisterName.BX),
                        new MovInstruction(RegisterName.CX, -5),
                        new StoreInstruction(RegisterName.DX),
                        new SubInstruction(RegisterName.AX)
                ),
                result
        );
    }

    @Test
    void shouldParseMnemonicsAndRegistersCaseInsensitively() {
        List<Instruction> result = parser.parse(List.of(
                "load ax",
                "StOrE bX",
                "aDd cX",
                "SuB dX"
        ));

        assertEquals(
                List.of(
                        new LoadInstruction(RegisterName.AX),
                        new StoreInstruction(RegisterName.BX),
                        new AddInstruction(RegisterName.CX),
                        new SubInstruction(RegisterName.DX)
                ),
                result
        );
    }

    @Test
    void shouldTolerateLeadingTrailingRepeatedAndTabWhitespace() {
        List<Instruction> result = parser.parse(List.of(
                "   LOAD AX   ",
                "STORE          BX",
                "ADD\tCX",
                "     SUB       DX     "
        ));

        assertEquals(
                List.of(
                        new LoadInstruction(RegisterName.AX),
                        new StoreInstruction(RegisterName.BX),
                        new AddInstruction(RegisterName.CX),
                        new SubInstruction(RegisterName.DX)
                ),
                result
        );
    }

    @Test
    void shouldTolerateVeryWideWhitespace() {
        List<Instruction> result = parser.parse(
                List.of("LOAD" + " ".repeat(1_000) + "AX")
        );

        assertEquals(List.of(new LoadInstruction(RegisterName.AX)), result);
    }

    @Test
    void shouldIgnoreBlankLines() {
        List<Instruction> result = parser.parse(List.of(
                "LOAD AX",
                "",
                "     ",
                "\t",
                "ADD BX"
        ));

        assertAll(
                () -> assertEquals(2, result.size()),
                () -> assertEquals(
                        List.of(
                                new LoadInstruction(RegisterName.AX),
                                new AddInstruction(RegisterName.BX)
                        ),
                        result
                )
        );
    }

    @Test
    void shouldPreserveOriginalPhysicalLineNumberAfterBlankLines() {
        AsmParseException exception = assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of(
                        "LOAD AX",
                        "",
                        "   ",
                        "ADD BX",
                        "WRONG CX"
                ))
        );

        assertEquals(5, exception.lineNumber());
    }

    @Test
    void shouldPreserveInstructionOrderAcrossBlankLines() {
        List<Instruction> result = parser.parse(List.of(
                "LOAD AX",
                "",
                "STORE BX",
                "",
                "",
                "ADD CX",
                "SUB DX"
        ));

        assertEquals(
                List.of(
                        new LoadInstruction(RegisterName.AX),
                        new StoreInstruction(RegisterName.BX),
                        new AddInstruction(RegisterName.CX),
                        new SubInstruction(RegisterName.DX)
                ),
                result
        );
    }

    @Test
    void shouldRejectUnknownMnemonicWithContext() {
        AsmParseException exception = assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of("FOO AX"))
        );

        assertAll(
                () -> assertEquals(1, exception.lineNumber()),
                () -> assertFalse(exception.getMessage().isBlank()),
                () -> assertInstanceOf(IllegalArgumentException.class, exception)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOAD X1", "ADD AC", "STORE ZZ"})
    void shouldRejectInvalidRegister(String sourceLine) {
        AsmParseException exception = assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of(sourceLine))
        );

        assertAll(
                () -> assertEquals(1, exception.lineNumber()),
                () -> assertFalse(exception.getMessage().isBlank())
        );
    }

    @Test
    void shouldRejectMissingOperand() {
        assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of("LOAD"))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADD AX BX", "STORE BX, 7"})
    void shouldRejectExtraOperands(String sourceLine) {
        assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of(sourceLine))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOAD AX,", "STORE BX,", "ADD CX,", "SUB DX,"})
    void shouldRejectCommasInRegisterInstructions(String sourceLine) {
        assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of(sourceLine))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOAD AX, 7", "STORE AX, 7", "ADD AX, 7", "SUB AX, 7"})
    void shouldKeepRegisterInstructionsStrictAfterAddingMov(String sourceLine) {
        assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of(sourceLine))
        );
    }

    @Test
    void shouldRequireSeparationBetweenMnemonicAndOperand() {
        assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of("LOADAX"))
        );
    }

    @Test
    void shouldReturnEmptyListForEmptyInput() {
        List<Instruction> result = parser.parse(List.of());

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyListForAllBlankInput() {
        List<Instruction> result = parser.parse(List.of("", " ", "       ", "\t"));

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldRejectNullSourceList() {
        assertThrows(NullPointerException.class, () -> parser.parse(null));
    }

    @Test
    void shouldRejectNullSourceLine() {
        List<String> sourceLines = new ArrayList<>();
        sourceLines.add("LOAD AX");
        sourceLines.add(null);
        sourceLines.add("ADD BX");

        assertThrows(NullPointerException.class, () -> parser.parse(sourceLines));
    }

    @Test
    void shouldReturnImmutableResult() {
        List<Instruction> result = parser.parse(List.of("MOV AX, 5", "LOAD AX"));

        assertAll(
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> result.add(new AddInstruction(RegisterName.BX))
                ),
                () -> assertThrows(UnsupportedOperationException.class, result::clear)
        );
    }

    @Test
    void shouldNotLeakStateBetweenSuccessfulParses() {
        List<Instruction> firstResult = parser.parse(List.of("MOV AX, 10", "LOAD AX"));

        List<Instruction> secondResult = parser.parse(List.of("SUB DX"));

        assertAll(
                () -> assertEquals(
                        List.of(
                                new MovInstruction(RegisterName.AX, 10),
                                new LoadInstruction(RegisterName.AX)
                        ),
                        firstResult
                ),
                () -> assertEquals(
                        List.of(new SubInstruction(RegisterName.DX)),
                        secondResult
                )
        );
    }

    @Test
    void shouldRemainReusableAfterAtomicParseFailure() {
        AsmParseException exception = assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of(
                        "LOAD AX",
                        "MOV BX, 25",
                        "ADD CX",
                        "MOV DX, 128",
                        "STORE AX"
                ))
        );

        List<Instruction> result = parser.parse(List.of("STORE DX"));

        assertAll(
                () -> assertEquals(4, exception.lineNumber()),
                () -> assertInstanceOf(
                        InvalidImmediateValueException.class,
                        exception.getCause()
                ),
                () -> assertEquals(
                        List.of(new StoreInstruction(RegisterName.DX)),
                        result
                )
        );
    }
}
