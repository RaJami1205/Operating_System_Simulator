package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.operand.*;
import io.github.rajami1205.osimulator.model.process.ProcessStack;
import io.github.rajami1205.osimulator.model.process.exception.ProcessStackOverflowException;
import io.github.rajami1205.osimulator.model.cpu.exception.InvalidRegisterValueException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControlFlowStackModelTest {
    @Test void metadataAndOperandViewsAreTypedOrderedAndImmutable() {
        var displacement = new BranchDisplacement(Integer.MAX_VALUE);
        assertEquals(Integer.MIN_VALUE, new BranchDisplacement(Integer.MIN_VALUE).value());
        var ax = RegisterName.AX; var bx = RegisterName.BX;
        var values = new ArrayList<>(List.of(new ImmediateOperand(1), new ImmediateOperand(2)));
        var param = new ParamInstruction(values);
        values.clear();
        var instructions = List.<Instruction>of(new CmpInstruction(ax,bx), new JmpInstruction(displacement),
                new JeInstruction(displacement), new JneInstruction(displacement), param,
                new PushInstruction(ax), new PopInstruction(bx));
        var opcodes = List.of(Opcode.CMP,Opcode.JMP,Opcode.JE,Opcode.JNE,Opcode.PARAM,Opcode.PUSH,Opcode.POP);
        var operands = List.of(List.of(new RegisterOperand(ax), new RegisterOperand(bx)),
                List.of(displacement),List.of(displacement),List.of(displacement),
                List.of(new ImmediateOperand(1),new ImmediateOperand(2)),List.of(new RegisterOperand(ax)),List.of(new RegisterOperand(bx)));
        for (int i=0;i<instructions.size();i++) {
            var instruction=instructions.get(i);
            assertEquals(opcodes.get(i),instruction.opcode());
            assertEquals(i<4?2:i==4?3:1,instruction.executionWeight().ticks());
            assertEquals(operands.get(i),instruction.operands());
            assertThrows(UnsupportedOperationException.class,()->instruction.operands().clear());
        }
        assertEquals(List.of(new ImmediateOperand(1),new ImmediateOperand(2)),param.values());
        assertThrows(UnsupportedOperationException.class,()->param.values().clear());
        assertThrows(IllegalArgumentException.class,()->new MovInstruction(ax,displacement));
        assertEquals(new MovInstruction(ax,new ImmediateOperand(1)),new MovInstruction(ax,1));
        assertEquals(new MovInstruction(ax,new RegisterOperand(bx)),new MovInstruction(ax,bx));
    }
    @Test void constructorsRejectNullAndInvalidCounts() {
        assertThrows(NullPointerException.class,()->new CmpInstruction(null,RegisterName.AX));
        assertThrows(NullPointerException.class,()->new CmpInstruction(RegisterName.AX,null));
        assertThrows(NullPointerException.class,()->new JmpInstruction(null));
        assertThrows(NullPointerException.class,()->new JeInstruction(null));
        assertThrows(NullPointerException.class,()->new JneInstruction(null));
        assertThrows(NullPointerException.class,()->new PushInstruction(null));
        assertThrows(NullPointerException.class,()->new PopInstruction(null));
        assertThrows(NullPointerException.class,()->new ParamInstruction(null));
        assertThrows(NullPointerException.class,()->new ParamInstruction(Arrays.asList(new ImmediateOperand(1),null)));
        assertThrows(IllegalArgumentException.class,()->new ParamInstruction(List.of()));
        assertThrows(IllegalArgumentException.class,()->new ParamInstruction(Collections.nCopies(4,new ImmediateOperand(0))));
    }
    @Test void bulkStackValidationIsAtomicAndNotLimitedToThreeValues() {
        var stack=new ProcessStack();
        stack.push(9);
        assertThrows(NullPointerException.class,()->stack.pushAll(null));
        assertThrows(NullPointerException.class,()->stack.pushAll(Arrays.asList(1,null)));
        for(int invalid:new int[]{-32769,32768})
            assertThrows(InvalidRegisterValueException.class,()->stack.pushAll(List.of(1,invalid)));
        assertThrows(ProcessStackOverflowException.class,()->stack.pushAll(List.of(1,2,3,4,5)));
        assertEquals(List.of(9),stack.values());
        var input=new ArrayList<>(List.of(-32768,32767,1,2));
        stack.pushAll(input); input.clear();
        assertEquals(List.of(9,-32768,32767,1,2),stack.values());
        stack.pushAll(List.of());
        assertEquals(2,stack.pop()); assertEquals(1,stack.pop());
    }
}
