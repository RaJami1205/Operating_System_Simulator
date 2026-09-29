package io.github.rajami1205.osimulator.infrastructure.asm;

import io.github.rajami1205.osimulator.infrastructure.asm.exception.AsmParseException;
import io.github.rajami1205.osimulator.model.instruction.InterruptInstruction;
import io.github.rajami1205.osimulator.model.instruction.operand.InterruptVector;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class InterruptParserTest {
    @Test void acceptsExactlyTheSupportedFormsWithExistingSourceConventions() {
        assertEquals(List.of(new InterruptInstruction(InterruptVector.KEYBOARD),new InterruptInstruction(InterruptVector.SCREEN),new InterruptInstruction(InterruptVector.TERMINATE)),
                new AsmParser().parse(List.of("; comment","", " iNt\t09h ; input", "INT 10H", "int 20h")));
    }
    @ParameterizedTest
    @ValueSource(strings={"INT","INT 9","INT 09","INT 0x09","INT20H","INT 11H","INT 21H","INT 10H, 20H","INT 10H 20H","INT AX"})
    void rejectsMalformedVectorsWithPhysicalLine(String source) {
        var failure=assertThrows(AsmParseException.class,()->new AsmParser().parse(List.of(";header","",source)));
        assertEquals(3,failure.lineNumber());
    }
}
