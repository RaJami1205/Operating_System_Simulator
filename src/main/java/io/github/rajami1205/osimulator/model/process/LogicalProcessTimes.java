package io.github.rajami1205.osimulator.model.process;

import java.util.Objects;
import java.util.OptionalLong;

/** Segundos lógicos inmutables; independientes del accounting real y del layout Kernel. */
public record LogicalProcessTimes(long arrivalTime, OptionalLong admissionTime,
        OptionalLong firstDispatchTime, OptionalLong finishTime) {
    public LogicalProcessTimes {
        Objects.requireNonNull(admissionTime);
        Objects.requireNonNull(firstDispatchTime);
        Objects.requireNonNull(finishTime);
        if (arrivalTime < 0
                || (admissionTime.isPresent() && admissionTime.getAsLong() < arrivalTime)
                || (firstDispatchTime.isPresent() && (admissionTime.isEmpty()
                    || firstDispatchTime.getAsLong() < admissionTime.getAsLong()))
                || (finishTime.isPresent() && (firstDispatchTime.isEmpty()
                    || finishTime.getAsLong() <= firstDispatchTime.getAsLong()))) {
            throw new IllegalArgumentException("Invalid logical process event order");
        }
    }

    public static LogicalProcessTimes admitted(long arrival, long admission) {
        return new LogicalProcessTimes(arrival, OptionalLong.of(admission), OptionalLong.empty(), OptionalLong.empty());
    }

    public LogicalProcessTimes dispatchedAt(long time) {
        if (admissionTime.isEmpty() || time < admissionTime.getAsLong() || finishTime.isPresent()
                || (firstDispatchTime.isPresent() && time < firstDispatchTime.getAsLong())) {
            throw new IllegalArgumentException("Invalid logical dispatch time");
        }
        return firstDispatchTime.isPresent() ? this
                : new LogicalProcessTimes(arrivalTime, admissionTime, OptionalLong.of(time), finishTime);
    }

    public LogicalProcessTimes finishedAt(long time) {
        if (finishTime.isPresent()) throw new IllegalStateException("Logical finish already recorded");
        return new LogicalProcessTimes(arrivalTime, admissionTime, firstDispatchTime, OptionalLong.of(time));
    }
}
