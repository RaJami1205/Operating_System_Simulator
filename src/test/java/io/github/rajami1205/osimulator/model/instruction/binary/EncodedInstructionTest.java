package io.github.rajami1205.osimulator.model.instruction.binary;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EncodedInstructionTest {

    @Test
    void shouldAcceptOneWord() {
        List<BinaryWord> words = List.of(new BinaryWord("0"));

        assertEquals(words, new EncodedInstruction(words).words());
    }

    @Test
    void shouldAcceptTwoWordsAndPreserveOrder() {
        BinaryWord first = new BinaryWord("0");
        BinaryWord second = new BinaryWord("1010");

        assertEquals(
                List.of(first, second),
                new EncodedInstruction(List.of(first, second)).words()
        );
    }

    @Test
    void shouldAcceptMoreThanTwoGenericWords() {
        List<BinaryWord> words = List.of(
                new BinaryWord("0"),
                new BinaryWord("10"),
                new BinaryWord("111")
        );

        assertEquals(words, new EncodedInstruction(words).words());
    }

    @Test
    void shouldAcceptMoreThanThreeGenericWords() {
        List<BinaryWord> words = List.of(
                new BinaryWord("0"),
                new BinaryWord("1"),
                new BinaryWord("10"),
                new BinaryWord("1010")
        );

        assertEquals(words, new EncodedInstruction(words).words());
    }

    @Test
    void shouldAcceptWordsWithDifferentGenericWidths() {
        List<BinaryWord> words = List.of(
                new BinaryWord("0"),
                new BinaryWord("1111111111111111")
        );

        assertEquals(words, new EncodedInstruction(words).words());
    }

    @Test
    void shouldRejectNullWordsList() {
        assertThrows(NullPointerException.class, () -> new EncodedInstruction(null));
    }

    @Test
    void shouldRejectEmptyWordsList() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EncodedInstruction(List.of())
        );
    }

    @Test
    void shouldRejectNullWordElement() {
        List<BinaryWord> words = new ArrayList<>();
        words.add(new BinaryWord("0"));
        words.add(null);

        assertThrows(NullPointerException.class, () -> new EncodedInstruction(words));
    }

    @Test
    void shouldDefensivelyCopySourceList() {
        List<BinaryWord> source = new ArrayList<>();
        source.add(new BinaryWord("0"));
        source.add(new BinaryWord("1"));
        EncodedInstruction encoded = new EncodedInstruction(source);

        source.set(0, new BinaryWord("11"));
        source.remove(1);
        source.add(new BinaryWord("1010"));

        assertEquals(
                List.of(new BinaryWord("0"), new BinaryWord("1")),
                encoded.words()
        );
    }

    @Test
    void shouldExposeUnmodifiableWordsList() {
        EncodedInstruction encoded = new EncodedInstruction(
                List.of(new BinaryWord("0"), new BinaryWord("1"))
        );

        assertAll(
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> encoded.words().add(new BinaryWord("10"))
                ),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> encoded.words().remove(0)
                ),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> encoded.words().set(0, new BinaryWord("10"))
                )
        );
    }

    @Test
    void shouldUseOrderedWordsAsRecordValue() {
        BinaryWord first = new BinaryWord("0");
        BinaryWord second = new BinaryWord("1");
        EncodedInstruction encoded = new EncodedInstruction(List.of(first, second));
        EncodedInstruction equal = new EncodedInstruction(List.of(first, second));
        EncodedInstruction reordered = new EncodedInstruction(List.of(second, first));

        assertEquals(encoded, equal);
        assertEquals(encoded.hashCode(), equal.hashCode());
        assertNotEquals(encoded, reordered);
    }
}
