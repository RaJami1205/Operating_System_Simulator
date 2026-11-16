package io.github.rajami1205.osimulator.model.scheduling;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExactProductsTest {
    @Test void matchesExactReferenceInBothDirections() {
        long[] factors = {0, 1, 2, 3, Integer.MAX_VALUE, 1L << 62, Long.MAX_VALUE};
        for (long a : factors) for (long b : factors) {
            for (long c : factors) for (long d : factors) {
                var left = BigDecimal.valueOf(a).multiply(BigDecimal.valueOf(b));
                var right = BigDecimal.valueOf(c).multiply(BigDecimal.valueOf(d));
                int result = Integer.signum(ExactProducts.compare(a, b, c, d));
                assertEquals(left.compareTo(right), result);
                assertEquals(-result, Integer.signum(ExactProducts.compare(c, d, a, b)));
            }
        }
    }

    @Test void preservesTransitivityBeyondLongRange() {
        long n = Long.MAX_VALUE;
        assertTrue(ExactProducts.compare(n, n - 2, n - 1, n - 1) < 0);
        assertTrue(ExactProducts.compare(n - 1, n - 1, n, n - 1) < 0);
        assertTrue(ExactProducts.compare(n, n - 2, n, n - 1) < 0);
    }
}
