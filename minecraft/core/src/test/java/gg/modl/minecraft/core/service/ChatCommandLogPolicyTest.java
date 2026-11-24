package gg.modl.minecraft.core.service;

import gg.modl.minecraft.core.chat.CommandMatcher;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCommandLogPolicyTest {
    private static final String UUID = "11111111-1111-1111-1111-111111111111";

    @Test
    void chatLoggingOffStoresNoChatButKeepsCommands() {
        ChatCommandLogService service = withPolicy(new ChatCommandLogPolicy(false, true, CommandMatcher.none()));

        service.addChatMessage(UUID, "Notch", "hello", "lobby");
        service.addCommand(UUID, "Notch", "/spawn", "lobby");

        assertTrue(service.drainChatBuffer().isEmpty());
        assertEquals(1, service.drainCommandBuffer().size());
    }

    @Test
    void commandLoggingOffStoresNoCommandsButKeepsChat() {
        ChatCommandLogService service = withPolicy(new ChatCommandLogPolicy(true, false, CommandMatcher.none()));

        service.addChatMessage(UUID, "Notch", "hello", "lobby");
        service.addCommand(UUID, "Notch", "/spawn", "lobby");

        assertEquals(1, service.drainChatBuffer().size());
        assertTrue(service.drainCommandBuffer().isEmpty());
    }

    @Test
    void neverLoggedCommandsAreSkippedInEveryForm() {
        ChatCommandLogService service = withPolicy(new ChatCommandLogPolicy(true, true,
                CommandMatcher.of(Collections.singletonList("msg"), Collections::singleton)));

        service.addCommand(UUID, "Notch", "/msg Alex secret", "lobby");
        service.addCommand(UUID, "Notch", "/essentials:msg Alex secret", "lobby");
        service.addCommand(UUID, "Notch", "/spawn", "lobby");

        assertEquals(1, service.drainCommandBuffer().size());
    }

    @Test
    void everythingIsLoggedByDefault() {
        ChatCommandLogService service = new ChatCommandLogService();

        service.addChatMessage(UUID, "Notch", "hello", "lobby");
        service.addCommand(UUID, "Notch", "/msg Alex hi", "lobby");

        assertEquals(1, service.drainChatBuffer().size());
        assertEquals(1, service.drainCommandBuffer().size());
    }

    private static ChatCommandLogService withPolicy(ChatCommandLogPolicy policy) {
        ChatCommandLogService service = new ChatCommandLogService();
        service.setPolicy(policy);
        return service;
    }
}
