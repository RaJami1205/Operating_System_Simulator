package io.github.rajami1205.osimulator.application.program;

import io.github.rajami1205.osimulator.application.program.exception.ProgramImportException;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import java.nio.file.Path;
import java.util.List;

/** Imports semantic instructions without loading them into a simulator session. */
public interface ProgramImporter {

    /**
     * Returns an immutable instruction list, which may be empty.
     *
     * @throws NullPointerException if path is null
     * @throws ProgramImportException if the source cannot be read or parsed
     */
    List<Instruction> importProgram(Path path) throws ProgramImportException;
}
