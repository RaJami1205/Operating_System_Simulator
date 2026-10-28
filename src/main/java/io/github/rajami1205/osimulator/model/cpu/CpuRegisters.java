package io.github.rajami1205.osimulator.model.cpu;

import io.github.rajami1205.osimulator.model.cpu.exception.InvalidProgramCounterException;
import java.util.EnumMap;
import java.util.Objects;
import java.util.Optional;

/**
 * Estado activo y mutable del único CPU, distinto del CpuContext guardado en cada PCB. Valida datos signed
 * 16-bit y conserva DX/AL tipados.
 */
public final class CpuRegisters<I> {

    private final EnumMap<RegisterName, Integer> generalRegisters =
            new EnumMap<>(RegisterName.class);
    private int accumulator;
    private int programCounter;
    private I instructionRegister;
    private int ah;
    private RegisterValue dx;
    private RegisterValue al;
    private ConditionFlags conditionFlags;

    /** Inicializa los registros numéricos y el IR del procesador simulado. */
    public CpuRegisters() {
        restoreInitialState();
    }

    /** Expone el valor lógico almacenado en AC. */
    public int accumulator() {
        return accumulator;
    }

    /** Valida el rango lógico antes de actualizar AC. */
    public void writeAccumulator(int value) {
        CpuValueRange.validateRegisterValue(value);
        accumulator = value;
    }

    /** Consulta un registro general no nulo; DX textual produce RegisterTypeMismatchException. */
    public int readRegister(RegisterName register) {
        return requireRegister(register) == RegisterName.DX ? dx.numericValue() : generalRegisters.get(register);
    }

    /** Valida el registro y el rango lógico antes de escribir su valor. */
    public void writeRegister(RegisterName register, int value) {
        RegisterName nonNullRegister = requireRegister(register);
        CpuValueRange.validateRegisterValue(value);
        if (nonNullRegister == RegisterName.DX) dx = new NumericRegisterValue(value);
        else generalRegisters.put(nonNullRegister, value);
    }

    /** Expone la posición actual del contador de programa. */
    public int programCounter() {
        return programCounter;
    }

    /** Exige PC lógico no negativo, sin aplicarle el rango signed 16-bit de datos. */
    public void setProgramCounter(int address) {
        if (address < 0) {
            throw new InvalidProgramCounterException(
                    "Program Counter address must not be negative: " + address
            );
        }

        programCounter = address;
    }

    /** Expone la instrucción del IR, si existe. */
    public Optional<I> instructionRegister() {
        return Optional.ofNullable(instructionRegister);
    }

    /** Conserva en IR la instrucción recibida para ejecución. */
    public void loadInstructionRegister(I instruction) {
        instructionRegister = Objects.requireNonNull(
                instruction,
                "instruction must not be null"
        );
    }

    /** Descarta la instrucción actualmente almacenada en IR. */
    public void clearInstructionRegister() {
        instructionRegister = null;
    }

    /** Consulta el registro lógico numérico de servicio AH, independiente de AX. */
    public int ah() {
        return ah;
    }

    /** Valida signed 16-bit antes de reemplazar AH, sin modificar AX ni AL. */
    public void writeAh(int value) {
        CpuValueRange.validateRegisterValue(value);
        ah = value;
    }

    /** Consulta AL como número; falla si actualmente contiene texto. */
    public int al() {
        return al.numericValue();
    }

    /**
     * Reemplaza AL numérico o tipado, validando rango en el overload numérico y rechazando referencias
     * nulas.
     */
    public void writeAl(int value) {
        CpuValueRange.validateRegisterValue(value);
        al = new NumericRegisterValue(value);
    }

    /** Expone el valor inmutable tipado de DX sin convertirlo a número. */
    public RegisterValue dxValue() { return dx; }
    /** Expone el valor inmutable tipado de AL sin convertirlo a número. */
    public RegisterValue alValue() { return al; }
    /** Reemplaza DX por un valor tipado no nulo sin afectar otros registros. */
    public void writeDx(RegisterValue value) { dx = Objects.requireNonNull(value, "DX must not be null"); }
    /**
     * Reemplaza AL numérico o tipado, validando rango en el overload numérico y rechazando referencias
     * nulas.
     */
    public void writeAl(RegisterValue value) { al = Objects.requireNonNull(value, "AL must not be null"); }

    /** Consulta las flags activas del CPU, distintas de las guardadas en PCB. */
    public ConditionFlags conditionFlags() {
        return conditionFlags;
    }

    /** Sustituye las flags por un valor inmutable no nulo; no las calcula automáticamente. */
    public void writeConditionFlags(ConditionFlags flags) {
        conditionFlags = Objects.requireNonNull(flags, "flags must not be null");
    }

    /** Captura todos los registros; el contenido de IR debe ser inmutable. */
    public CpuContext<I> snapshot() {
        return new CpuContext<>(programCounter, instructionRegister(), accumulator,
                readRegister(RegisterName.AX), readRegister(RegisterName.BX),
                readRegister(RegisterName.CX), dx, ah, al, conditionFlags);
    }

    /** Restaura un contexto ya validado sin compartir el mapa mutable de registros. */
    public void restore(CpuContext<I> context) {
        Objects.requireNonNull(context, "context must not be null");
        programCounter = context.programCounter();
        instructionRegister = context.instructionRegister().orElse(null);
        accumulator = context.accumulator();
        generalRegisters.put(RegisterName.AX, context.ax());
        generalRegisters.put(RegisterName.BX, context.bx());
        generalRegisters.put(RegisterName.CX, context.cx());
        dx = context.dxValue();
        ah = context.ah();
        al = context.alValue();
        conditionFlags = context.conditionFlags();
    }

    /** Restablece los registros numéricos y vacía el IR. */
    public void reset() {
        restoreInitialState();
    }

    /** Lleva los registros numéricos a cero y deja el IR vacío. */
    private void restoreInitialState() {
        accumulator = 0;
        ah = 0;
        dx = new NumericRegisterValue(0);
        al = new NumericRegisterValue(0);
        conditionFlags = ConditionFlags.CLEAR;

        for (RegisterName register : RegisterName.values()) {
            if (register != RegisterName.DX) generalRegisters.put(register, 0);
        }

        programCounter = 0;
        instructionRegister = null;
    }

    /** Rechaza referencias nulas a registros generales. */
    private RegisterName requireRegister(RegisterName register) {
        return Objects.requireNonNull(register, "register must not be null");
    }

}
