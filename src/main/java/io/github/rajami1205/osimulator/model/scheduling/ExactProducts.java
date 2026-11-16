package io.github.rajami1205.osimulator.model.scheduling;

import java.math.BigInteger;

final class ExactProducts {
    private ExactProducts() { }

    /** Compara los productos cruzados de factores no negativos de scheduling. */
    static int compare(long a, long b, long c, long d) {
        var left = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b));
        var right = BigInteger.valueOf(c).multiply(BigInteger.valueOf(d));
        return left.compareTo(right);
    }
}
