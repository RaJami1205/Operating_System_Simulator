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
}
