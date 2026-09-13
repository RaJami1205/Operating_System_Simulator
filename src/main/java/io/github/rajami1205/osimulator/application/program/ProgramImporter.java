package io.github.rajami1205.osimulator.application.program;

import io.github.rajami1205.osimulator.application.program.exception.ProgramImportException;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import java.nio.file.Path;
import java.util.List;

/** Importa instrucciones semánticas sin cargarlas en una sesión del simulador. */
public interface ProgramImporter {

    /**
     * Devuelve una lista inmutable de instrucciones, que puede estar vacía.
     *
     * @throws NullPointerException si path es nulo
     * @throws ProgramImportException si no se puede leer o analizar el archivo de origen
     */
    List<Instruction> importProgram(Path path) throws ProgramImportException;
}
