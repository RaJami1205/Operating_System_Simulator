package io.github.rajami1205.osimulator.model.process;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.rajami1205.osimulator.model.process.exception.InvalidProcessConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProcessControlBlockTest {

    @Test
    void shouldDefineExactlyTheApprovedProcessStates() {
        assertArrayEquals(
                new ProcessState[]{
                    ProcessState.NEW,
                    ProcessState.READY,
                    ProcessState.RUNNING,
                    ProcessState.BLOCKED,
                    ProcessState.TERMINATED
                },
                ProcessState.values()
        );
    }

    @Test
    void shouldExposeMetadataAndInitialRuntimeState() {
        ProcessControlBlock pcb = new ProcessControlBlock(1, 100, 5);

        assertAll(
                () -> assertEquals(1, pcb.processId()),
                () -> assertEquals(100, pcb.programStartAddress()),
                () -> assertEquals(5, pcb.instructionCount()),
                () -> assertEquals(105, pcb.programEndAddressExclusive()),
                () -> assertEquals(ProcessState.NEW, pcb.state()),
                () -> assertEquals(100, pcb.programCounter())
        );
    }

    @Test
    void shouldAcceptZeroAsProgramStartAddress() {
        ProcessControlBlock pcb = new ProcessControlBlock(1, 0, 1);

        assertAll(
                () -> assertEquals(0, pcb.programStartAddress()),
                () -> assertEquals(1, pcb.programEndAddressExclusive()),
                () -> assertEquals(0, pcb.programCounter())
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {1, Integer.MAX_VALUE})
    void shouldAcceptValidProcessIdBoundaries(int processId) {
        ProcessControlBlock pcb = new ProcessControlBlock(processId, 0, 1);

        assertEquals(processId, pcb.processId());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100, Integer.MIN_VALUE})
    void shouldRejectNonPositiveProcessIds(int processId) {
        assertInvalidConfiguration(processId, 0, 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -100, Integer.MIN_VALUE})
    void shouldRejectNegativeProgramStartAddresses(int programStartAddress) {
        assertInvalidConfiguration(1, programStartAddress, 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 100})
    void shouldAcceptPositiveInstructionCounts(int instructionCount) {
        ProcessControlBlock pcb = new ProcessControlBlock(1, 0, instructionCount);

        assertEquals(instructionCount, pcb.instructionCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void shouldRejectNonPositiveInstructionCounts(int instructionCount) {
        assertInvalidConfiguration(1, 0, instructionCount);
    }

    @ParameterizedTest
    @CsvSource({
            "100, 5, 105",
            "0, 1, 1"
    })
    void shouldDeriveExclusiveProgramEndAddress(
            int programStartAddress,
            int instructionCount,
            int expectedEndAddress
    ) {
        ProcessControlBlock pcb = new ProcessControlBlock(
                1,
                programStartAddress,
                instructionCount
        );

        assertEquals(expectedEndAddress, pcb.programEndAddressExclusive());
    }

    @Test
    void shouldAcceptMaximumRepresentableProgramEndAddress() {
        int programStartAddress = Integer.MAX_VALUE - 1;

        ProcessControlBlock pcb = new ProcessControlBlock(1, programStartAddress, 1);

        assertAll(
                () -> assertEquals(Integer.MAX_VALUE, pcb.programEndAddressExclusive()),
                () -> assertEquals(programStartAddress, pcb.programCounter())
        );
    }

    @ParameterizedTest
    @CsvSource({
            "2147483647, 1",
            "2147483646, 2"
    })
    void shouldRejectProgramAddressRangeOverflow(
            int programStartAddress,
            int instructionCount
    ) {
        assertInvalidConfiguration(1, programStartAddress, instructionCount);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 100, Integer.MAX_VALUE - 1})
    void shouldInitializeRuntimeStateForRepresentativeStartAddresses(
            int programStartAddress
    ) {
        ProcessControlBlock pcb = new ProcessControlBlock(1, programStartAddress, 1);

        assertAll(
                () -> assertEquals(ProcessState.NEW, pcb.state()),
                () -> assertEquals(programStartAddress, pcb.programCounter()),
                () -> assertEquals(pcb.programStartAddress(), pcb.programCounter())
        );
    }

    private void assertInvalidConfiguration(
            int processId,
            int programStartAddress,
            int instructionCount
    ) {
        InvalidProcessConfigurationException exception = assertThrows(
                InvalidProcessConfigurationException.class,
                () -> new ProcessControlBlock(
                        processId,
                        programStartAddress,
                        instructionCount
                )
        );

        assertAll(
                () -> assertNotNull(exception.getMessage()),
                () -> assertFalse(exception.getMessage().isBlank())
        );
    }
}
