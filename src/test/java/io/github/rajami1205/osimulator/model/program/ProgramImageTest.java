package io.github.rajami1205.osimulator.model.program;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProgramImageTest {
    @Test
    void validatesAndDefensivelyCopiesWithoutNormalizingNames() {
        Instruction instruction = new LoadInstruction(RegisterName.AX);
        var input = new ArrayList<Instruction>(List.of(instruction));
        var program = new ProgramImage(" A.asm ", input);
        input.clear();
        assertEquals(" A.asm ", program.logicalName());
        assertEquals(List.of(instruction), program.instructions());
        assertThrows(UnsupportedOperationException.class, () -> program.instructions().clear());
        assertEquals(program, new ProgramImage(" A.asm ", List.of(instruction)));
        assertNotEquals(program, new ProgramImage(" a.asm ", List.of(instruction)));
        assertThrows(NullPointerException.class, () -> new ProgramImage(null, List.of(instruction)));
        for (var name : List.of("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException.class, () -> new ProgramImage(name, List.of(instruction)));
        }
        assertThrows(NullPointerException.class, () -> new ProgramImage("p", null));
        assertThrows(IllegalArgumentException.class, () -> new ProgramImage("p", List.of()));
        assertThrows(NullPointerException.class, () -> new ProgramImage("p", Arrays.asList(instruction, null)));
    }
}
