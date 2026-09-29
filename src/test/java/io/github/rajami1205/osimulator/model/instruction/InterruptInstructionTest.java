package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.operand.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InterruptInstructionTest {
    @Test void exactlyThreeTypedVectorsOwnTheirMetadataAndCannotBeMovSources() {
        assertEquals(List.of("09H","10H","20H"),java.util.Arrays.stream(InterruptVector.values()).map(InterruptVector::canonicalText).toList());
        for(var vector:InterruptVector.values()) {
            var instruction=new InterruptInstruction(vector);
            assertEquals(Opcode.INT,instruction.opcode()); assertEquals(List.of(vector),instruction.operands());
            assertSame(vector.executionWeight(),instruction.executionWeight()); assertEquals(2,instruction.executionWeight().ticks());
            assertThrows(UnsupportedOperationException.class,()->instruction.operands().clear());
            assertThrows(IllegalArgumentException.class,()->new MovInstruction(RegisterName.AX,vector));
        }
        assertThrows(NullPointerException.class,()->new InterruptInstruction(null));
        assertThrows(IllegalArgumentException.class,()->InterruptVector.parse("21H"));
        assertThrows(IllegalArgumentException.class,()->new MovInstruction(RegisterName.AX,new BranchDisplacement(0)));
        assertEquals(InterruptVector.KEYBOARD,InterruptVector.parse("09h"));
    }
}
