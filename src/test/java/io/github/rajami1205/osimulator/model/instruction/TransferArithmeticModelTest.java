package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.operand.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransferArithmeticModelTest {
    @Test
    void typedFormsPreserveMetadataEqualityAndWeight() {
        assertEquals(Optional.empty(), new IncInstruction().target());
        assertEquals(Optional.empty(), new DecInstruction().target());
        assertTrue(new IncInstruction().operands().isEmpty());
        assertTrue(new DecInstruction().operands().isEmpty());
        for (var left : RegisterName.values()) {
            var inc = new IncInstruction(left);
            var dec = new DecInstruction(left);
            assertEquals(new IncInstruction(Optional.of(left)), inc);
            assertEquals(new DecInstruction(Optional.of(left)), dec);
            assertEquals(List.of(new RegisterOperand(left)), inc.operands());
            assertEquals(inc.operands(), dec.operands());
            for (var right : RegisterName.values()) {
                var mov = new MovInstruction(left, right);
                var swap = new SwapInstruction(left, right);
                var expected = List.of(new RegisterOperand(left), new RegisterOperand(right));
                assertEquals(expected, mov.operands());
                assertEquals(expected, swap.operands());
                assertEquals(new MovInstruction(left, new RegisterOperand(right)), mov);
                for (Instruction instruction : List.of(mov, swap, inc, dec, new IncInstruction(), new DecInstruction())) {
                    assertEquals(1, instruction.executionWeight().ticks());
                    assertTrue(instruction.getClass().isRecord());
                    assertThrows(UnsupportedOperationException.class,
                            () -> instruction.operands().add(new ImmediateOperand(0)));
                }
            }
        }
        assertEquals(new MovInstruction(RegisterName.AX, new ImmediateOperand(5)), new MovInstruction(RegisterName.AX, 5));
        assertNotEquals(new MovInstruction(RegisterName.AX, 5), new MovInstruction(RegisterName.AX, RegisterName.BX));
        assertEquals(Set.of(MovInstruction.class, LoadInstruction.class, StoreInstruction.class,
                AddInstruction.class, SubInstruction.class, IncInstruction.class, DecInstruction.class, SwapInstruction.class, CmpInstruction.class,
                JmpInstruction.class, JeInstruction.class, JneInstruction.class, ParamInstruction.class,
                PushInstruction.class, PopInstruction.class, InterruptInstruction.class),
                Set.of(Instruction.class.getPermittedSubclasses()));
    }

    @Test
    void rejectsNullOperandsWithoutAddingRegisterKinds() {
        assertThrows(NullPointerException.class, () -> new MovInstruction(RegisterName.AX, (InstructionOperand) null));
        assertThrows(NullPointerException.class, () -> new MovInstruction(RegisterName.AX, (RegisterName) null));
        assertThrows(NullPointerException.class, () -> new IncInstruction((Optional<RegisterName>) null));
        assertThrows(NullPointerException.class, () -> new DecInstruction((Optional<RegisterName>) null));
        assertThrows(NullPointerException.class, () -> new IncInstruction((RegisterName) null));
        assertThrows(NullPointerException.class, () -> new DecInstruction((RegisterName) null));
        assertThrows(NullPointerException.class, () -> new SwapInstruction(null, RegisterName.AX));
        assertThrows(NullPointerException.class, () -> new SwapInstruction(RegisterName.AX, null));
    }
}
