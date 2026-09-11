package io.github.rajami1205.osimulator.infrastructure.asm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rajami1205.osimulator.application.program.ProgramImporter;
import io.github.rajami1205.osimulator.application.program.exception.ProgramImportException;
import io.github.rajami1205.osimulator.infrastructure.asm.exception.AsmParseException;
import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.LoadInstruction;
import io.github.rajami1205.osimulator.model.instruction.MovInstruction;
import io.github.rajami1205.osimulator.model.instruction.StoreInstruction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AsmFileProgramImporterTest {

    @TempDir
    Path directory;

    private final ProgramImporter importer = new AsmFileProgramImporter(new AsmParser());

    @Test
    void importsUtf8InstructionsAndDelegatesBlankLinesWithoutFilteringExtension() throws Exception {
        // U+2003 is a UTF-8 whitespace character recognized by the parser's isBlank().
        Path file = write("program.txt", "\u2003\nMOV AX, 5\n\nLOAD AX\nSTORE BX\n");
        var instructions = importer.importProgram(file);
        assertEquals(List.of(new MovInstruction(RegisterName.AX, 5),
                new LoadInstruction(RegisterName.AX), new StoreInstruction(RegisterName.BX)), instructions);
        assertThrows(UnsupportedOperationException.class, instructions::clear);
    }

    @Test
    void acceptsEmptyAndBlankOnlyFiles() throws Exception {
        assertTrue(importer.importProgram(write("empty.asm", "")).isEmpty());
        assertTrue(importer.importProgram(write("blank.asm", "\n \n\t\n")).isEmpty());
    }

    @Test
    void translatesInvalidAsmAndPreservesCauseAndPhysicalLineNumber() throws Exception {
        Path file = write("invalid.asm", "MOV AX, 5\n\nLOAD AX\nINVALID AX\n");
        var exception = assertThrows(ProgramImportException.class, () -> importer.importProgram(file));
        var cause = assertInstanceOf(AsmParseException.class, exception.getCause());
        assertEquals(4, cause.lineNumber());
        assertEquals("Invalid ASM program at line 4", exception.getMessage());
    }

    @Test
    void leavesCommentHandlingToParser() throws Exception {
        Path file = write("comment.asm", "MOV AX, 5\n; comentario\n");
        var exception = assertThrows(ProgramImportException.class, () -> importer.importProgram(file));
        var cause = assertInstanceOf(AsmParseException.class, exception.getCause());
        assertEquals(2, cause.lineNumber());
    }

    @Test
    void translatesMissingFileAndPreservesIoCause() {
        var exception = assertThrows(ProgramImportException.class,
                () -> importer.importProgram(directory.resolve("missing.asm")));
        assertInstanceOf(IOException.class, exception.getCause());
        assertEquals("Unable to read ASM program", exception.getMessage());
    }

    @Test
    void translatesMalformedUtf8AsReadFailure() throws IOException {
        Path file = directory.resolve("malformed.asm");
        Files.write(file, new byte[] {(byte) 0xC3, (byte) 0x28});
        var exception = assertThrows(ProgramImportException.class, () -> importer.importProgram(file));
        assertInstanceOf(IOException.class, exception.getCause());
        assertEquals("Unable to read ASM program", exception.getMessage());
    }

    @Test
    void rejectsNullDependencyAndPath() {
        assertThrows(NullPointerException.class, () -> new AsmFileProgramImporter(null));
        assertThrows(NullPointerException.class, () -> importer.importProgram(null));
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(directory.resolve(name), content, StandardCharsets.UTF_8);
    }
}
