package gg.modl.minecraft.core.service;

import gg.modl.minecraft.api.http.ChatLogEntry;
import gg.modl.minecraft.api.http.CommandLogEntry;
import gg.modl.minecraft.api.http.response.CommandLogsResponse;
import gg.modl.minecraft.core.TestHttpClients;
import gg.modl.minecraft.core.support.FakeModlHttpClient;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCommandLogServiceTest {

    private static final String UUID = "11111111-1111-1111-1111-111111111111";

    private static String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) builder.append(c);
        return builder.toString();
    }

    @Test
    void blankChatMessageIsNotBuffered() {
        ChatCommandLogService service = new ChatCommandLogService();
        service.addChatMessage(UUID, "Notch", "   ", "srv");
        service.addChatMessage(UUID, "Notch", "", "srv");
        service.addChatMessage(UUID, "Notch", null, "srv");
        assertTrue(service.drainChatBuffer().isEmpty());
    }

    @Test
    void blankCommandIsNotBuffered() {
        ChatCommandLogService service = new ChatCommandLogService();
        service.addCommand(UUID, "Notch", " ", "srv");
        assertTrue(service.drainCommandBuffer().isEmpty());
    }

    @Test
    void oversizeChatMessageIsTruncatedToContractLength() {
        ChatCommandLogService service = new ChatCommandLogService();
        service.addChatMessage(UUID, "Notch", repeat('a', 600), "srv");
        List<ChatLogEntry> drained = service.drainChatBuffer();
        assertEquals(1, drained.size());
        assertEquals(512, drained.get(0).getMessage().length());
    }

    @Test
    void oversizeCommandIsTruncatedToContractLength() {
        ChatCommandLogService service = new ChatCommandLogService();
        service.addCommand(UUID, "Notch", "/" + repeat('b', 600), "srv");
        List<CommandLogEntry> drained = service.drainCommandBuffer();
        assertEquals(1, drained.size());
        assertEquals(512, drained.get(0).getCommand().length());
    }

    @Test
    void redactionHappensBeforeTruncation() {
        ChatCommandLogService service = new ChatCommandLogService();
        service.addCommand(UUID, "Notch", "/login " + repeat('p', 600), "srv");

        assertEquals("/login [REDACTED]", service.drainCommandBuffer().get(0).getCommand());
    }

    @Test
    void truncatingARedactedCommandDoesNotBringTheSecretBack() {
        ChatCommandLogService service = new ChatCommandLogService();
        service.addCommand(UUID, "Notch", "/" + repeat('n', 480) + ":login hunter2" + repeat('p', 100), "srv");

        assertEquals("/" + repeat('n', 480) + ":login [REDACTED]", service.drainCommandBuffer().get(0).getCommand());
    }

    @Test
    void storedHistoryIsRedactedWhenShown() {
        ChatCommandLogService service = new ChatCommandLogService();
        FakeModlHttpClient client = new FakeModlHttpClient() {
            @Override
            public CompletableFuture<CommandLogsResponse> getCommandLogs(String playerUuid, int limit) {
                return CompletableFuture.completedFuture(new CommandLogsResponse(Arrays.asList(
                        new CommandLogEntry(UUID, "Notch", "/login hunter2", "lobby", 1L),
                        new CommandLogEntry(UUID, "Notch", "/home base", "survival", 2L))));
            }
        };

        List<CommandLogEntry> shown = service.getCommandLogs(TestHttpClients.connected(client), UUID, 10).join();

        assertEquals("/login [REDACTED]", shown.get(0).getCommand());
        assertEquals("lobby", shown.get(0).getServer());
        assertEquals(1L, shown.get(0).getTimestamp());
        assertEquals("/home base", shown.get(1).getCommand());
    }

    @Test
    void truncationDoesNotSplitSurrogatePair() {
        ChatCommandLogService service = new ChatCommandLogService();
        service.addChatMessage(UUID, "Notch", repeat('a', 511) + "😀xyz", "srv");
        List<ChatLogEntry> drained = service.drainChatBuffer();
        String message = drained.get(0).getMessage();
        assertEquals(511, message.length());
        assertEquals('a', message.charAt(510));
    }

    @Test
    void contractLengthMessagePassesThroughUnchanged() {
        ChatCommandLogService service = new ChatCommandLogService();
        String exact = repeat('c', 512);
        service.addChatMessage(UUID, "Notch", exact, "srv");
        assertEquals(exact, service.drainChatBuffer().get(0).getMessage());
    }
}
