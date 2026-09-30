package io.github.rajami1205.osimulator.model.cpu;

import io.github.rajami1205.osimulator.model.cpu.exception.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegisterValueTest {
    @Test void immutableValuesValidateAndNeverCoerce() {
        for(int value:new int[]{-32768,0,32767}) assertEquals(value,new NumericRegisterValue(value).numericValue());
        for(int value:new int[]{-32769,32768}) assertThrows(InvalidRegisterValueException.class,()->new NumericRegisterValue(value));
        assertThrows(NullPointerException.class,()->new TextRegisterValue(null));
        assertEquals("",new TextRegisterValue("").textValue());
        assertThrows(RegisterTypeMismatchException.class,()->new TextRegisterValue("42").numericValue());
        assertThrows(RegisterTypeMismatchException.class,()->new NumericRegisterValue(42).textValue());
    }
    @Test void contextPreservesTextWhileNumericCompatibilityRemainsStrict() {
        var cpu=new CpuRegisters<String>();
        cpu.writeDx(new TextRegisterValue("notes.txt")); cpu.writeAl(new TextRegisterValue("hello")); cpu.writeAh(60);
        assertThrows(RegisterTypeMismatchException.class,()->cpu.readRegister(RegisterName.DX));
        assertThrows(RegisterTypeMismatchException.class,cpu::al);
        var saved=cpu.snapshot(); cpu.reset();
        assertEquals(0,cpu.readRegister(RegisterName.DX)); assertEquals(0,cpu.al());
        cpu.restore(saved.withProgramCounter(8));
        assertEquals("notes.txt",cpu.dxValue().textValue()); assertEquals("hello",cpu.alValue().textValue());
        assertEquals(60,cpu.ah()); assertEquals(8,cpu.programCounter());
        assertThrows(RegisterTypeMismatchException.class,saved::dx);
        assertThrows(RegisterTypeMismatchException.class,saved::al);
        cpu.writeRegister(RegisterName.DX,7);cpu.writeAl(8);
        assertEquals(7,cpu.readRegister(RegisterName.DX));assertEquals(8,cpu.al());
    }
}
