package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.instruction.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserBlockReadTest {
    @Test void requiresOriginalActiveUserIdentityAndCompleteImmutableImage() {
        var memory = new MainMemory(new MemoryConfiguration(128,32));
        var user = memory.allocateUser(2);
        assertThrows(RuntimeException.class, () -> memory.readUserBlock(user));
        var image = List.<Instruction>of(new IncInstruction(), new DecInstruction());
        memory.writeUserBlock(user, image);
        var read = memory.readUserBlock(user);
        assertEquals(image, read);
        assertThrows(UnsupportedOperationException.class, read::clear);
        memory.writeInstruction(user, 0, new DecInstruction());
        assertEquals(image, read);
        assertEquals(List.of(new DecInstruction(),new DecInstruction()), memory.readUserBlock(user));
        var kernel = memory.allocateKernel(2);
        assertThrows(RuntimeException.class, () -> memory.readUserBlock(kernel));
        var foreign = new MainMemory(new MemoryConfiguration(128,32)).allocateUser(2);
        assertThrows(RuntimeException.class, () -> memory.readUserBlock(foreign));
        memory.release(user);
        assertThrows(RuntimeException.class, () -> memory.readUserBlock(user));
        var current = memory.allocateUser(2);
        memory.writeUserBlock(current, image);
        assertThrows(RuntimeException.class, () -> memory.readUserBlock(user));
        memory.reset();
        assertThrows(RuntimeException.class, () -> memory.readUserBlock(current));
    }
}
