package io.github.rajami1205.osimulator.model.instruction.binary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BinaryWordTest {

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "1010", "00000000", "11111111"})
    void shouldAcceptBinaryValuesOfDifferentWidths(String bits) {
        BinaryWord word = new BinaryWord(bits);

        assertEquals(bits, word.bits());
    }

    @ParameterizedTest
    @CsvSource({
        "0, 1",
        "1010, 4",
        "00100000, 8"
    })
    void shouldDeriveWidthFromBits(String bits, int expectedWidth) {
        assertEquals(expectedWidth, new BinaryWord(bits).width());
    }

    @Test
    void shouldRejectNullBits() {
        assertThrows(NullPointerException.class, () -> new BinaryWord(null));
    }

    @Test
    void shouldRejectEmptyBits() {
        assertThrows(IllegalArgumentException.class, () -> new BinaryWord(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "102",
        "abc",
        "00 01",
        " 00000000 ",
        "+101",
        "-101",
        "1_0"
    })
    void shouldRejectNonBinaryCharactersWithoutNormalization(String bits) {
        assertThrows(IllegalArgumentException.class, () -> new BinaryWord(bits));
    }
}
