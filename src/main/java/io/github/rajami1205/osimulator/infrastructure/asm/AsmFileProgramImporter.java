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

/** Reads UTF-8 ASM files and delegates their syntax entirely to the parser. */
public final class AsmFileProgramImporter implements ProgramImporter {

    private final AsmParser asmParser;

    public AsmFileProgramImporter(AsmParser asmParser) {
        this.asmParser = Objects.requireNonNull(asmParser, "asmParser must not be null");
    }

    @Override
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
