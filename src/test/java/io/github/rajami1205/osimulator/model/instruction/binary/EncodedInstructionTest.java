package io.github.rajami1205.osimulator.model.instruction.binary;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        EncodedInstruction encoded = new EncodedInstruction(source);

        source.add(new BinaryWord("1"));

        assertEquals(List.of(new BinaryWord("0")), encoded.words());
    }

    @Test
    void shouldExposeUnmodifiableWordsList() {
        EncodedInstruction encoded = new EncodedInstruction(
                List.of(new BinaryWord("0"))
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> encoded.words().add(new BinaryWord("1"))
        );
    }
}
