package io.github._4drian3d.signedvelocity.paper.listener;

import io.github._4drian3d.signedvelocity.common.queue.SignedQueue;
import io.github._4drian3d.signedvelocity.common.queue.SignedResult;
import io.github._4drian3d.signedvelocity.shared.logger.DebugLogger;
import io.github._4drian3d.signedvelocity.shared.types.QueueType;
import io.github._4drian3d.signedvelocity.shared.types.ResultType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginMessagingListenerTest {
    private static final UUID PLAYER = UUID.fromString("1bade2a0-c703-3923-95c2-55c2a99049fd");
    private static final UUID OTHER = UUID.fromString("131baf7b-eb1f-3ca0-be24-69637807dc38");

    private final SignedQueue chatQueue = new SignedQueue();
    private final SignedQueue commandQueue = new SignedQueue();
    private final PluginMessagingListener listener = new PluginMessagingListener(chatQueue, commandQueue, new SilentLogger());

    @AfterEach
    void close() {
        chatQueue.close();
        commandQueue.close();
    }

    @Test
    void aVerdictTakenBeforeTheChatEventIsWaitingForIt() throws IOException {
        listener.accept(PLAYER, payload(PLAYER, QueueType.CHAT, ResultType.CANCEL));

        CompletableFuture<SignedResult> next = chatQueue.dataFrom(PLAYER).nextResult();

        assertTrue(next.isDone());
        assertTrue(next.join().cancelled());
    }

    @Test
    void aCommandVerdictGoesToTheCommandQueue() throws IOException {
        listener.accept(PLAYER, payload(PLAYER, QueueType.COMMAND, ResultType.CANCEL));

        assertTrue(commandQueue.dataFrom(PLAYER).nextResult().join().cancelled());
        assertFalse(chatQueue.dataFrom(PLAYER).nextResult().join().cancelled());
    }

    @Test
    void aVerdictNamingAnotherPlayerIsDropped() throws IOException {
        listener.accept(PLAYER, payload(OTHER, QueueType.CHAT, ResultType.CANCEL));

        assertFalse(chatQueue.dataFrom(PLAYER).nextResult().join().cancelled());
        assertFalse(chatQueue.dataFrom(OTHER).nextResult().join().cancelled());
    }

    @Test
    void aMalformedPayloadIsDropped() {
        listener.accept(PLAYER, new byte[] {1, 2, 3});

        assertFalse(chatQueue.dataFrom(PLAYER).nextResult().join().cancelled());
    }

    private static byte[] payload(UUID player, QueueType queue, ResultType result) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF(player.toString());
            output.writeUTF(queue.value());
            output.writeUTF(result.value());
        }
        return bytes.toByteArray();
    }

    private static final class SilentLogger implements DebugLogger {
        @Override
        public void debugMultiple(Supplier<String[]> supplier) {
        }

        @Override
        public void debug(Supplier<String> supplier) {
        }
    }
}
