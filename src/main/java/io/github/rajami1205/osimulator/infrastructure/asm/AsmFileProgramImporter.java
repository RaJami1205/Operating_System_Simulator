package io.github.rajami1205.osimulator.infrastructure.asm;

import io.github.rajami1205.osimulator.application.program.ProgramImporter;
import io.github.rajami1205.osimulator.application.program.exception.ProgramImportException;
import io.github.rajami1205.osimulator.infrastructure.asm.exception.AsmParseException;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Lee archivos ASM en UTF-8 y delega toda su sintaxis al parser. */
public final class AsmFileProgramImporter implements ProgramImporter {

    private final AsmParser asmParser;

    // Recibe el parser al que se delegará toda la sintaxis ASM.
    public AsmFileProgramImporter(AsmParser asmParser) {
        this.asmParser = Objects.requireNonNull(asmParser, "asmParser must not be null");
    }

    @Override
    // Lee el archivo en UTF-8 y delega su análisis, traduciendo errores de lectura o sintaxis.
    public List<Instruction> importProgram(Path path) throws ProgramImportException {
        Objects.requireNonNull(path, "path must not be null");
        try {
            return asmParser.parse(Files.readAllLines(path, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new ProgramImportException("Unable to read ASM program", exception);
        } catch (AsmParseException exception) {
            throw new ProgramImportException(
                    "Invalid ASM program at line " + exception.lineNumber(), exception);
        }
    }
}
