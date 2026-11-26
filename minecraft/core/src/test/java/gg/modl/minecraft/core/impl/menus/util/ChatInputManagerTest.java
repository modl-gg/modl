package gg.modl.minecraft.core.impl.menus.util;

import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.MapLocaleManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatInputManagerTest {
    private static final UUID STAFF = UUID.randomUUID();

    private final FakePlatform platform = new FakePlatform();
    private final ChatInputManager chatInput = new ChatInputManager(platform, new MapLocaleManager().withFallback(path -> path));
    private final List<String> answers = new ArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    @Test
    void skippingAnOptionalPromptAnswersWithNothing() {
        chatInput.requestOptionalInput(STAFF, "Reason", answers::add, () -> cancelled.set(true));

        assertTrue(chatInput.handleChat(STAFF, "SKIP"));

        assertEquals(1, answers.size());
        assertNull(answers.get(0));
        assertFalse(cancelled.get());
    }

    @Test
    void anOptionalPromptShowsTheSkipHint() {
        chatInput.requestOptionalInput(STAFF, "Reason", answers::add, () -> cancelled.set(true));

        assertTrue(platform.sentMessages().stream().anyMatch(sent -> sent.message().equals("menus.chat_input.optional_hint")));
    }

    @Test
    void cancelStillCancelsAnOptionalPrompt() {
        chatInput.requestOptionalInput(STAFF, "Reason", answers::add, () -> cancelled.set(true));

        assertTrue(chatInput.handleChat(STAFF, "cancel"));

        assertTrue(answers.isEmpty());
        assertTrue(cancelled.get());
    }

    @Test
    void aRequiredPromptTakesSkipAsItsAnswer() {
        chatInput.requestInput(STAFF, "Reason", answers::add, () -> cancelled.set(true));

        assertTrue(chatInput.handleChat(STAFF, "skip"));

        assertEquals(1, answers.size());
        assertEquals("skip", answers.get(0));
        assertTrue(platform.sentMessages().stream().anyMatch(sent -> sent.message().equals("menus.chat_input.hint")));
    }
}
