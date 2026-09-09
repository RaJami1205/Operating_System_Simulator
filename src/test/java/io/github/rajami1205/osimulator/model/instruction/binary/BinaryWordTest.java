package io.github.rajami1205.osimulator.model.instruction.binary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        "01, 2",
        "1010, 4",
        "00100000, 8",
        "1111111111111111, 16"
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
        "2",
        "102",
        "01a1",
        "abc",
        "0 1",
        "00 01",
        "\t01",
        "01\n",
        " 00000000 ",
        "+1",
        "+101",
        "-1",
        "-101",
        "1_0",
        "０１"
    })
    void shouldRejectNonBinaryCharactersWithoutNormalization(String bits) {
        assertThrows(IllegalArgumentException.class, () -> new BinaryWord(bits));
    }

    @Test
    void shouldUseBitsAsRecordValue() {
        BinaryWord first = new BinaryWord("1010");
        BinaryWord equal = new BinaryWord("1010");
        BinaryWord different = new BinaryWord("0101");

        assertEquals(first, equal);
        assertEquals(first.hashCode(), equal.hashCode());
        assertNotEquals(first, different);
    }
}
