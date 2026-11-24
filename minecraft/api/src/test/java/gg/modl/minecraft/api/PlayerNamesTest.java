package gg.modl.minecraft.api;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerNamesTest {
    @Test
    void javaNamesAndBedrockPrefixesAreAccepted() {
        for (String name : Arrays.asList("Steve", "x", "Notch_123", ".Bedrock", "*Bedrock", "+Bedrock", ".A23456789012345")) {
            assertTrue(PlayerNames.isValid(name), name);
        }
    }

    @Test
    void namesThatCouldChangeAConsoleCommandAreRefused() {
        for (String name : Arrays.asList(null, "", ".", "*", "..Steve", "Steve.", "St eve", "Steve;op me", "@a",
                "Steve\nop", "A2345678901234567", ".A234567890123456", "\"Steve\"", "Steve&")) {
            assertFalse(PlayerNames.isValid(name), String.valueOf(name));
        }
    }
}
