package io.github.rajami1205.osimulator.model.filesystem;

import io.github.rajami1205.osimulator.model.cpu.CpuRegisters;
import io.github.rajami1205.osimulator.model.cpu.TextRegisterValue;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import io.github.rajami1205.osimulator.model.storage.exception.StorageException;
import java.util.Objects;

/**
 * Servicios INT21 de sesión: DX nombra el archivo, WRITE toma texto de AL y READ lo devuelve en AL.
 * SecondaryStorage posee contenido e índice; no se escribe al host filesystem.
 */
public final class SimulatedFileSystem {
    private final SecondaryStorage storage;
    /** Recibe el SecondaryStorage canónico de la sesión; no construye archivos del host. */
    public SimulatedFileSystem(SecondaryStorage storage) { this.storage = Objects.requireNonNull(storage); }

    /**
     * Ejecuta el servicio AH sobre el nombre textual DX y OpenFileTable del PCB; READ/WRITE usan AL y los
     * errores se traducen a FileSystemException.
     */
    public void execute(CpuRegisters<Instruction> cpu, ProcessControlBlock pcb) {
        Objects.requireNonNull(cpu); Objects.requireNonNull(pcb);
        try {
            var service = FileService.fromCode(cpu.ah());
            String name = cpu.dxValue().textValue();
            switch (service) {
                case CREATE -> storage.createUserFile(name);
                case OPEN -> {
                    storage.userFile(name);
                    pcb.openFiles().open(name);
                }
                case READ -> {
                    requireOpen(pcb, name);
                    cpu.writeAl(new TextRegisterValue(storage.readUserFile(name)));
                }
                case WRITE -> {
                    String content = cpu.alValue().textValue();
                    requireOpen(pcb, name);
                    storage.writeUserFile(name, content);
                }
                case DELETE -> {
                    storage.deleteUserFile(name);
                    pcb.openFiles().close(name);
                }
            }
        } catch (StorageException | IllegalArgumentException exception) {
            throw new FileSystemException("File service failed: " + exception.getMessage(), exception);
        }
    }
    /** Comprueba que el nombre sea USER_FILE existente y esté abierto por este PCB antes de READ/WRITE. */
    private void requireOpen(ProcessControlBlock pcb, String name) {
        storage.userFile(name);
        if (!pcb.openFiles().contains(name)) throw new FileSystemException("File is not open: " + name);
    }
}
