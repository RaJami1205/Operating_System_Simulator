package io.github.rajami1205.osimulator.model.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AddressValuesTest {
    @Test void addressesAreTypedNonnegativeValues() {
        assertEquals(0, new PhysicalAddress(0).value());
        assertEquals(0, new LogicalAddress(0).value());
        assertEquals(new LogicalAddress(17), new LogicalAddress(17));
        assertNotEquals(new LogicalAddress(17), new PhysicalAddress(17));
        assertEquals(Integer.MAX_VALUE, new PhysicalAddress(Integer.MAX_VALUE).value());
        assertThrows(IllegalArgumentException.class, () -> new LogicalAddress(-1));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalAddress(-1));
    }

    @Test void codeRangeRequiresPositiveBaseLengthAndRepresentableTerminal() {
        var range = new LogicalCodeRange(new LogicalAddress(100), 3);
        assertEquals(new LogicalAddress(103), range.endExclusive());
        assertEquals(3, range.length());
        assertEquals(new LogicalAddress(Integer.MAX_VALUE),
                new LogicalCodeRange(new LogicalAddress(Integer.MAX_VALUE - 1), 1).endExclusive());
        assertThrows(NullPointerException.class, () -> new LogicalCodeRange(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new LogicalCodeRange(new LogicalAddress(0), 1));
        for (int length : new int[]{0, -1, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new LogicalCodeRange(new LogicalAddress(1), length));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new LogicalCodeRange(new LogicalAddress(Integer.MAX_VALUE), 1));
    }
}
