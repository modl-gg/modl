package gg.modl.minecraft.core.chat;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandMatcherTest {
    @Test
    void matchesTheLabelWithOrWithoutSlashNamespaceOrCase() {
        CommandMatcher matcher = CommandMatcher.of(Collections.singletonList("/Tell"), Collections::singleton);

        assertTrue(matcher.matches("tell Notch hi"));
        assertTrue(matcher.matches("/TELL"));
        assertTrue(matcher.matches("//minecraft:tell Notch"));
        assertFalse(matcher.matches("/teller"));
        assertFalse(matcher.matches("/minecraft:"));
        assertFalse(matcher.matches(" "));
        assertFalse(matcher.matches(null));
    }

    @Test
    void matchesEveryNameThePlatformResolvesForTheLabel() {
        CommandMatcher matcher = CommandMatcher.of(Collections.singletonList("msg"),
                label -> "whisperplus".equals(label) ? Arrays.asList("whisperplus", "essentials:msg") : Collections.singleton(label));

        assertTrue(matcher.matches("/whisperplus Notch hi"));
        assertFalse(matcher.matches("/spawn"));
    }

    @Test
    void anEmptyListMatchesNothingWithoutAskingThePlatform() {
        CommandMatcher matcher = CommandMatcher.of(Collections.emptyList(), label -> {
            throw new AssertionError("the platform must not be asked");
        });

        assertFalse(matcher.matches("/msg Notch"));
        assertFalse(CommandMatcher.none().matches("/msg Notch"));
    }
}
