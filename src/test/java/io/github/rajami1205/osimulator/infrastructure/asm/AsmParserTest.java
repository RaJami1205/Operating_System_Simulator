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
import io.github.rajami1205.osimulator.model.instruction.StoreInstruction;
import io.github.rajami1205.osimulator.model.instruction.SubInstruction;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
        List<Instruction> result = parser.parse(List.of("LOAD AX"));

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
        List<Instruction> firstResult = parser.parse(List.of("LOAD AX", "ADD BX"));

        List<Instruction> secondResult = parser.parse(List.of("SUB DX"));

        assertAll(
                () -> assertEquals(
                        List.of(
                                new LoadInstruction(RegisterName.AX),
                                new AddInstruction(RegisterName.BX)
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
        assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of("LOAD AX", "ADD BX", "WRONG CX", "STORE DX"))
        );

        List<Instruction> result = parser.parse(List.of("STORE DX"));

        assertEquals(List.of(new StoreInstruction(RegisterName.DX)), result);
    }

    @Test
    void shouldNotParseMovDuringTaskOne() {
        assertThrows(
                AsmParseException.class,
                () -> parser.parse(List.of("MOV AX, 5"))
        );
    }
}
