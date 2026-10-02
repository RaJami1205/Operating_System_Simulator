package io.github.rajami1205.osimulator.presentation;

import io.github.rajami1205.osimulator.application.simulator.SimulatorSnapshot.*;
import io.github.rajami1205.osimulator.model.process.ProcessAccounting;

/**
 * Formatea contexto y accounting de snapshots inmutables. Convierte timestamps a hora local y elapsed a
 * horas y segundos totales sin consultar el Clock del runtime.
 */
final class DashboardDetails {
    /** Impide instanciar el helper de formatting de detalles. */
    private DashboardDetails() {}
    /** Formatea registros y flags de una vista CPU, conservando valores tipados y marcadores de ausencia. */
    static String context(CpuSnapshot cpu) {
        return "PC: " + cpu.programCounter() + "   IR: " + cpu.instructionRegister().orElse("\u2014")
                + "\nAC: " + cpu.accumulator() + "   AX: " + cpu.ax() + "   BX: " + cpu.bx() + "   CX: " + cpu.cx()
                + "\nDX: " + cpu.dxText() + "   AH: " + cpu.ah() + "   AL: " + cpu.alText()
                + "\nEqual: " + cpu.flags().equal() + "   Overflow: " + cpu.flags().overflow();
    }
    /**
     * Formatea timestamps en zona local o explícita y elapsed en horas totales; no consulta Clock ni
     * inventa duración para procesos incompletos.
     */
    static String accounting(ProcessAccounting value) {
        return accounting(value, java.time.ZoneId.systemDefault());
    }
    /**
     * Formatea timestamps en zona local o explícita y elapsed en horas totales; no consulta Clock ni
     * inventa duración para procesos incompletos.
     */
    static String accounting(ProcessAccounting value, java.time.ZoneId zone) {
        var format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss a", java.util.Locale.ENGLISH)
                .withZone(zone);
        return "\nCPU ID: " + (value.cpuId().isPresent() ? value.cpuId().getAsInt() : "—")
                + "   Start Time: " + value.startTime().map(format::format).orElse("—")
                + "   CPU Ticks: " + value.cpuTicks()
                + "\nFinish Time: " + value.finishTime().map(format::format).orElse("—")
                + "   Elapsed Time: " + value.elapsedTime().map(duration -> String.format(java.util.Locale.ROOT,
                        "%02d:%02d:%02d", duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart())).orElse("—")
                + "\nDuration (s): " + value.elapsedTime().map(duration -> Long.toString(duration.toSeconds())).orElse("—");
    }
    /** Compone el detalle de PCB guardado, residencia, recursos y accounting del snapshot seleccionado. */
    static String process(ProcessDetails value) {
        return "PID " + value.processId() + " | " + value.state() + " | " + value.residency()
                + "\nBase: " + value.base().map(Object::toString).orElse("\u2014") + "   Limit: " + value.limit()
                + "   Priority: " + value.priority() + "\nKernel PCB: " + value.kernelAddress()
                + "   Next PCB: " + value.nextPcbAddress().map(Object::toString).orElse("\u2014")
                + "\nSaved context (distinct from active CPU):\n" + context(value.savedContext())
                + "\nStack (bottom to top): " + value.stack() + "\nOpen files: " + value.openFiles() + accounting(value.accounting());
    }
}
