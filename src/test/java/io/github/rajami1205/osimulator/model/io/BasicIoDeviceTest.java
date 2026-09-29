package io.github.rajami1205.osimulator.model.io;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BasicIoDeviceTest {
    @Test void screenProvidesOrderedDefensiveSnapshots() {
        var screen=new ScreenDevice(); assertTrue(screen.outputs().isEmpty());
        screen.append(-32768); var saved=screen.outputs(); screen.append(32767);
        assertEquals(List.of(-32768),saved); assertEquals(List.of(-32768,32767),screen.outputs());
        assertThrows(UnsupportedOperationException.class,()->saved.add(1));
    }
    @Test void keyboardValidatesAtomicallyAndPreservesFifo() {
        var keyboard=new KeyboardDevice(); assertTrue(keyboard.poll().isEmpty());
        keyboard.submit(0);
        assertThrows(IllegalArgumentException.class,()->keyboard.submit(-1));
        assertThrows(IllegalArgumentException.class,()->keyboard.submit(256));
        keyboard.submit(255);
        assertEquals(0,keyboard.poll().orElseThrow()); assertEquals(255,keyboard.poll().orElseThrow());
        assertFalse(keyboard.hasInput()); assertTrue(keyboard.poll().isEmpty());
    }
}
