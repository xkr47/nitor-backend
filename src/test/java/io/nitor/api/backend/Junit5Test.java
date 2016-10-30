package io.nitor.api.backend;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Test;

/**
 * http://junit.org/junit5/docs/current/user-guide/
 */
public class Junit5Test
{
    @Test
    @DisplayName("My 1st JUnit 5 test! 😎")
    void myFirstTest(TestInfo testInfo) {
        assertEquals(2, 1 + 1, "1 + 1 should equal 2");
        assertEquals("My 1st JUnit 5 test! 😎", testInfo.getDisplayName(), () -> "TestInfo is injected correctly");
    }
}
