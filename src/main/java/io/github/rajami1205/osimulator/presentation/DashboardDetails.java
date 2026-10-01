package io.github.rajami1205.osimulator.presentation;

import io.github.rajami1205.osimulator.application.simulator.SimulatorSnapshot.*;
import io.github.rajami1205.osimulator.model.process.ProcessAccounting;

/** Pure layout text for already-mapped immutable read values. */
final class DashboardDetails {
    private DashboardDetails() {}
    static String context(CpuSnapshot cpu) {
        return "PC: " + cpu.programCounter() + "   IR: " + cpu.instructionRegister().orElse("\u2014")
                + "\nAC: " + cpu.accumulator() + "   AX: " + cpu.ax() + "   BX: " + cpu.bx() + "   CX: " + cpu.cx()
                + "\nDX: " + cpu.dxText() + "   AH: " + cpu.ah() + "   AL: " + cpu.alText()
                + "\nEqual: " + cpu.flags().equal() + "   Overflow: " + cpu.flags().overflow();
    }
    static String accounting(ProcessAccounting value) {
        return "\nExisting accounting: CPU=" + (value.cpuId().isPresent() ? value.cpuId().getAsInt() : "\u2014")
                + "   Start=" + (value.startTime().isPresent() ? value.startTime().getAsLong() : "\u2014")
                + "   CPU time=" + value.cpuTime()
                + "   Finish=" + (value.finishTime().isPresent() ? value.finishTime().getAsLong() : "\u2014");
    }
    static String process(ProcessDetails value) {
        return "PID " + value.processId() + " | " + value.state() + " | " + value.residency()
                + "\nBase: " + value.base().map(Object::toString).orElse("\u2014") + "   Limit: " + value.limit()
                + "   Priority: " + value.priority() + "\nKernel PCB: " + value.kernelAddress()
                + "   Next PCB: " + value.nextPcbAddress().map(Object::toString).orElse("\u2014")
                + "\nSaved context (distinct from active CPU):\n" + context(value.savedContext())
                + "\nStack (bottom to top): " + value.stack() + "\nOpen files: " + value.openFiles() + accounting(value.accounting());
    }
}
