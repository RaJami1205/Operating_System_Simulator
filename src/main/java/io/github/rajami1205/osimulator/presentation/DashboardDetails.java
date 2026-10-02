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
        return accounting(value, java.time.ZoneId.systemDefault());
    }
    static String accounting(ProcessAccounting value, java.time.ZoneId zone) {
        var format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss a", java.util.Locale.ENGLISH)
                .withZone(zone);
        return "\nCPU ID: " + (value.cpuId().isPresent() ? value.cpuId().getAsInt() : "—")
                + "   Start Time: " + value.startTime().map(format::format).orElse("—")
                + "   CPU Ticks: " + value.cpuTicks()
                + "\nFinish Time: " + value.finishTime().map(format::format).orElse("—")
                + "   Elapsed Time: " + value.elapsedTime().map(duration -> String.format(java.util.Locale.ROOT,
                        "%02d:%02d:%02d", duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart())).orElse("—");
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
