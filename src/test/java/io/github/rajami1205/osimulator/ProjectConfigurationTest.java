package io.github.rajami1205.osimulator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class ProjectConfigurationTest {

    @Test
    void shouldUseJava25() {
        assertEquals(25, Runtime.version().feature());
    }

    @Test
    void shouldLoadJavaFx() {
        assertDoesNotThrow(
                () -> Class.forName("javafx.application.Application")
        );
    }
}