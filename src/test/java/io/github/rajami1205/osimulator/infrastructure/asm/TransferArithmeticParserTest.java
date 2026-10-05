package io.github.rajami1205.osimulator.infrastructure.asm;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.infrastructure.asm.exception.AsmParseException;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class TransferArithmeticParserTest {
    private final AsmParser parser = new AsmParser();

    @Test
    void parsesAllRegisterPairsOptionalFormsAndComments() {
        assertEquals(List.of(new IncInstruction(), new DecInstruction(), new MovInstruction(RegisterName.AX, -5)),
                parser.parse(List.of(" ; ignored", "\tinc ; AC", " dec;comment", "MOV AX, -5 ; trailing", " ")));
        for (var left : RegisterName.values()) {
            String l = left.name().toLowerCase(Locale.ROOT);
            assertEquals(List.of(new IncInstruction(left), new DecInstruction(left)),
                    parser.parse(List.of("  iNc\t" + l + ";comment", "dec " + l)));
            for (var right : RegisterName.values()) {
                String r = right.name().toLowerCase(Locale.ROOT);
                assertEquals(List.of(new MovInstruction(left, right), new SwapInstruction(left, right)),
                        parser.parse(List.of("mOv\t" + l + " , " + r, " swap " + l + "," + r + " ; comment")));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"INC 1", "INC AX,", "INC AX, BX", "INC ,", "DEC AC", "DEC AH", "DEC AX,",
            "DEC AX BX", "SWAP", "SWAP AX", "SWAP AX BX", "SWAP AX,", "SWAP , BX", "SWAP AX, 1",
            "SWAP AX, BX, CX", "MOV AC, BX", "MOV AX, AH", "MOV AX, PC", "MOV AX, IR", "MOV AX, UNKNOWN",
            "MOV AX, 32768", "MOV AX, -32769", "MOV AX, BX CX", "INCAX", "DEC 1.5"})
    void malformedOperandsKeepPhysicalLineNumbers(String source) {
        var error = assertThrows(AsmParseException.class,
                () -> parser.parse(List.of("; comment", "", "INC ; valid", source + " ; ignored")));
        assertEquals(4, error.lineNumber());
        assertFalse(error.getMessage().isBlank());
    }
}
