package io.github.rajami1205.osimulator.infrastructure.asm;

import io.github.rajami1205.osimulator.infrastructure.asm.exception.AsmParseException;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.instruction.operand.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ControlFlowStackParserTest {
    @Test void parsesCasingWhitespaceCommentsAndFullDisplacementRange() {
        var result=new AsmParser().parse(List.of("; header","", " cMp ax , BX ; comment", "jmp +2", "JMP -2", "jmp 0",
                "jE +2147483647", "jne -2147483648", "push\tAX", "pop bx", "PARAM 1", "param -32768, +32767", "PARAM 1, 2, 3"));
        assertEquals(List.of(Opcode.CMP,Opcode.JMP,Opcode.JMP,Opcode.JMP,Opcode.JE,Opcode.JNE,Opcode.PUSH,Opcode.POP,Opcode.PARAM,Opcode.PARAM,Opcode.PARAM),result.stream().map(Instruction::opcode).toList());
        assertEquals(new BranchDisplacement(Integer.MAX_VALUE),((JeInstruction)result.get(4)).displacement());
        assertEquals(new BranchDisplacement(Integer.MIN_VALUE),((JneInstruction)result.get(5)).displacement());
        assertEquals(List.of(new ImmediateOperand(-32768),new ImmediateOperand(32767)),((ParamInstruction)result.get(9)).values());
    }
    @ParameterizedTest
    @ValueSource(strings={"CMP", "CMP AX", "CMP AX BX", "CMP AX,1", "CMP AX,BX,CX", "CMP AH,BX",
            "JMP", "JMP AX", "JE 1.5", "JNE 1,2", "JMP 1 2", "JMP 2147483648", "JMP -2147483649",
            "PUSH", "PUSH 1", "POP 1", "POP AX BX", "PARAM", "PARAM 1,", "PARAM ,1", "PARAM 1,,2",
            "PARAM 1,2,3,4", "PARAM AX", "PARAM 32768", "PARAM -32769", "PARAM 1 2"})
    void malformedInstructionsPreservePhysicalLine(String text) {
        var failure=assertThrows(AsmParseException.class,()->new AsmParser().parse(List.of(";comment","",text)));
        assertEquals(3,failure.lineNumber());
    }
}
