package io.github.rajami1205.osimulator.model.cpu;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class ConditionFlagsTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void preservesIndependentFlags(boolean equal, boolean overflow) {
        var flags = new ConditionFlags(equal, overflow);
        assertEquals(equal, flags.equal());
        assertEquals(overflow, flags.overflow());
        assertEquals(new ConditionFlags(equal, overflow), flags);
        assertEquals(new ConditionFlags(false, false), ConditionFlags.CLEAR);
    }
}
