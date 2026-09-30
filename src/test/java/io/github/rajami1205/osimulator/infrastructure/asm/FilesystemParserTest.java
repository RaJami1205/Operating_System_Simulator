package io.github.rajami1205.osimulator.infrastructure.asm;

import io.github.rajami1205.osimulator.infrastructure.asm.exception.AsmParseException;
import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.instruction.operand.*;
import io.github.rajami1205.osimulator.model.filesystem.FileService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class FilesystemParserTest {
    @Test void parsesAllSelectorsAndPreservesQuotedData() {
        var parser=new AsmParser();
        for(var service:FileService.values()) {
            assertEquals(List.of(new MovInstruction(ServiceRegister.AH,service.code())),
                    parser.parse(List.of("mov ah, "+service.canonicalText().toLowerCase())));
        }
        assertEquals(List.of(new MovInstruction(RegisterName.DX,new TextOperand(" notes.txt ")),
                new MovInstruction(ServiceRegister.AL,new TextOperand("hello; A,B,C ")),
                new MovInstruction(ServiceRegister.AL,new TextOperand("")),new InterruptInstruction(InterruptVector.FILESYSTEM)),
                parser.parse(List.of("MOV DX, \" notes.txt \"","MOV AL, \"hello; A,B,C \" ; comment", "MOV AL, \"\"", "int 21h")));
        assertEquals(List.of(new MovInstruction(RegisterName.AX,60)),parser.parse(List.of("MOV AX, 3cH")));
    }
    @ParameterizedTest
    @ValueSource(strings={"MOV AL, \"unclosed", "MOV AL, \"a\" extra", "MOV AL, \"a\"\"b\"", "MOV AL, \"a\nb\"",
            "MOV AH, 3GH", "MOV AH, H", "MOV AH, FFFFFFFFH", "MOV AX, \"text\"", "MOV BX, \"\"", "MOV CX, \"x\"",
            "MOV AH, \"60\"", "MOV AL, 5", "MOV AL, AX", "MOV AH, BX", "ADD AH", "PUSH AL", "CMP AX, AH"})
    void rejectsMalformedOrIncompatibleOperandsWithPhysicalLine(String source) {
        var failure=assertThrows(AsmParseException.class,()->new AsmParser().parse(List.of("; header","",source)));
        assertEquals(3,failure.lineNumber());
    }
    @Test void modelRejectsInvalidCombinationsAndKeepsGeneralRegistersSeparate() {
        assertEquals(4,RegisterName.values().length);
        assertThrows(IllegalArgumentException.class,()->new MovInstruction(RegisterName.AX,new TextOperand("x")));
        assertThrows(IllegalArgumentException.class,()->new MovInstruction(ServiceRegister.AL,new ImmediateOperand(1)));
        assertThrows(IllegalArgumentException.class,()->new MovInstruction(ServiceRegister.AH,new RegisterOperand(RegisterName.DX)));
        var instruction=new MovInstruction(ServiceRegister.AL,new TextOperand("x"));
        assertEquals(List.of(new ServiceRegisterOperand(ServiceRegister.AL),new TextOperand("x")),instruction.operands());
        assertThrows(UnsupportedOperationException.class,()->instruction.operands().clear());
    }
}
