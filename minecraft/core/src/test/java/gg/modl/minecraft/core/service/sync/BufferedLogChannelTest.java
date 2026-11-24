package gg.modl.minecraft.core.service.sync;

import gg.modl.minecraft.api.http.ApiClientException;
import gg.modl.minecraft.api.http.ChatLogEntry;
import gg.modl.minecraft.api.http.PanelUnavailableException;
import gg.modl.minecraft.core.util.PluginLogger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BufferedLogChannelTest {

    private static final PluginLogger SILENT_LOGGER = new PluginLogger() {
        @Override public void info(String message) {}
        @Override public void warning(String message) {}
        @Override public void severe(String message) {}
    };

    private static ChatLogEntry chat(String username, String message) {
        return new ChatLogEntry("11111111-1111-1111-1111-111111111111", username, message, "srv", 1L);
    }

    private BufferedLogChannel<ChatLogEntry> channel(List<ChatLogEntry> source,
                                                     List<List<ChatLogEntry>> submitted,
                                                     CompletableFuture<Void> result) {
        return new BufferedLogChannel<>("chat", () -> new ArrayList<>(source),
                ChatLogEntry::getUsername, ChatLogEntry::getMessage,
                entries -> {
                    submitted.add(new ArrayList<>(entries));
                    return result;
                }, SILENT_LOGGER, 5000, 500);
    }

    @Test
    void blankMessagesAreDroppedBeforeSubmit() {
        List<ChatLogEntry> source = new ArrayList<>();
        source.add(chat("Notch", "hello"));
        source.add(chat("Notch", "   "));
        source.add(chat("Notch", ""));
        source.add(chat("Notch", null));
        List<List<ChatLogEntry>> submitted = new ArrayList<>();

        flushNow(channel(source, submitted, CompletableFuture.completedFuture(null)));

        assertEquals(1, submitted.size());
        assertEquals(1, submitted.get(0).size());
        assertEquals("hello", submitted.get(0).get(0).getMessage());
    }

    @Test
    void oversizeBatchIsChunkedToContractLimit() {
        List<ChatLogEntry> source = new ArrayList<>();
        for (int i = 0; i < 1101; i++) source.add(chat("Notch", "m" + i));
        List<List<ChatLogEntry>> submitted = new ArrayList<>();

        flushNow(channel(source, submitted, CompletableFuture.completedFuture(null)));

        assertEquals(3, submitted.size());
        assertEquals(500, submitted.get(0).size());
        assertEquals(500, submitted.get(1).size());
        assertEquals(101, submitted.get(2).size());
    }

    @Test
    void validationFailureDropsBatchAndDoesNotRetry() {
        List<ChatLogEntry> source = new ArrayList<>();
        source.add(chat("Notch", "hello"));
        List<List<ChatLogEntry>> submitted = new ArrayList<>();
        CompletableFuture<Void> rejected = failed(new ApiClientException(400, "value must contain"));

        BufferedLogChannel<ChatLogEntry> channel = channel(source, submitted, rejected);
        flushNow(channel);
        source.clear();
        CompletableFuture<Void> second = channel.flush();

        assertEquals(1, submitted.size());
        assertNotNull(second);
    }

    @Test
    void aRateLimitedBatchIsRebufferedInsteadOfDropped() {
        List<ChatLogEntry> source = new ArrayList<>();
        source.add(chat("Notch", "hello"));
        source.add(chat(".BedrockNotch", "hi"));
        List<List<ChatLogEntry>> submitted = new ArrayList<>();
        AtomicReference<CompletableFuture<Void>> result =
                new AtomicReference<>(failed(new ApiClientException(429, "Rate limit exceeded")));

        BufferedLogChannel<ChatLogEntry> channel = new BufferedLogChannel<>("chat",
                () -> new ArrayList<>(source), ChatLogEntry::getUsername, ChatLogEntry::getMessage,
                entries -> {
                    submitted.add(new ArrayList<>(entries));
                    return result.get();
                }, SILENT_LOGGER, 5000, 500);

        flushNow(channel);
        source.clear();
        result.set(CompletableFuture.completedFuture(null));
        flushNow(channel);

        assertEquals(2, submitted.size());
        assertEquals(2, submitted.get(1).size());
        assertEquals(".BedrockNotch", submitted.get(1).get(1).getUsername());
    }

    @Test
    void transientFailureRebuffersForRetry() {
        List<ChatLogEntry> source = new ArrayList<>();
        source.add(chat("Notch", "hello"));
        List<List<ChatLogEntry>> submitted = new ArrayList<>();
        AtomicReference<CompletableFuture<Void>> result =
                new AtomicReference<>(failed(new PanelUnavailableException("url", 503, "down")));

        BufferedLogChannel<ChatLogEntry> channel = new BufferedLogChannel<>("chat",
                () -> new ArrayList<>(source), ChatLogEntry::getUsername, ChatLogEntry::getMessage,
                entries -> {
                    submitted.add(new ArrayList<>(entries));
                    return result.get();
                }, SILENT_LOGGER, 5000, 500);

        flushNow(channel);
        source.clear();
        result.set(CompletableFuture.completedFuture(null));
        CompletableFuture<Void> retry = channel.flush();

        assertEquals(2, submitted.size());
        assertEquals("hello", submitted.get(1).get(0).getMessage());
        assertNotNull(retry);
    }

    @Test
    void anOutageIsReportedOnceUntilAnUploadSucceedsAgain() {
        List<ChatLogEntry> source = new ArrayList<>();
        source.add(chat("Notch", "hello"));
        List<String> warnings = new ArrayList<>();
        PluginLogger recording = new PluginLogger() {
            @Override public void info(String message) {}
            @Override public void warning(String message) { warnings.add(message); }
            @Override public void severe(String message) {}
        };
        AtomicReference<CompletableFuture<Void>> result =
                new AtomicReference<>(failed(new PanelUnavailableException("url", 503, "down")));
        BufferedLogChannel<ChatLogEntry> channel = new BufferedLogChannel<>("chat",
                () -> new ArrayList<>(source), ChatLogEntry::getUsername, ChatLogEntry::getMessage,
                entries -> result.get(), recording, 5000, 500);

        flushNow(channel);
        flushNow(channel);
        flushNow(channel);
        assertEquals(1, warnings.size());

        result.set(CompletableFuture.completedFuture(null));
        flushNow(channel);
        result.set(failed(new PanelUnavailableException("url", 503, "down")));
        flushNow(channel);

        assertEquals(2, warnings.size());
        assertEquals("Failed to upload chat logs: Panel temporarily unavailable; re-buffering", warnings.get(1));
    }

    @Test
    void aSubmitThatThrowsBeforeSendingRebuffersTheChunk() {
        List<ChatLogEntry> source = new ArrayList<>();
        source.add(chat("Notch", "hello"));
        List<List<ChatLogEntry>> submitted = new ArrayList<>();
        AtomicReference<Boolean> connected = new AtomicReference<>(false);
        BufferedLogChannel<ChatLogEntry> channel = new BufferedLogChannel<>("chat",
                () -> new ArrayList<>(source), ChatLogEntry::getUsername, ChatLogEntry::getMessage,
                entries -> {
                    if (!connected.get()) throw new IllegalStateException("not connected");
                    submitted.add(new ArrayList<>(entries));
                    return CompletableFuture.completedFuture(null);
                }, SILENT_LOGGER, 5000, 500);

        flushNow(channel);
        source.clear();
        connected.set(true);
        flushNow(channel);

        assertEquals(1, submitted.size());
        assertEquals("hello", submitted.get(0).get(0).getMessage());
    }

    @Test
    void anOverflowDuringOneOutageIsReportedOnce() {
        List<ChatLogEntry> source = new ArrayList<>();
        source.add(chat("Notch", "one"));
        source.add(chat("Notch", "two"));
        List<String> warnings = new ArrayList<>();
        PluginLogger recording = new PluginLogger() {
            @Override public void info(String message) {}
            @Override public void warning(String message) { warnings.add(message); }
            @Override public void severe(String message) {}
        };
        BufferedLogChannel<ChatLogEntry> channel = new BufferedLogChannel<>("chat",
                () -> new ArrayList<>(source), ChatLogEntry::getUsername, ChatLogEntry::getMessage,
                entries -> failed(new PanelUnavailableException("url", 503, "down")), recording, 1, 500);

        flushNow(channel);
        flushNow(channel);

        assertEquals(2, warnings.size());
        assertTrue(warnings.get(1).startsWith("Dropped 1 buffered chat logs"));
    }

    @Test
    void partialChunkFailureRebuffersOnlyFailedChunk() {
        List<ChatLogEntry> source = new ArrayList<>();
        for (int i = 0; i < 700; i++) source.add(chat("Notch", "m" + i));
        List<List<ChatLogEntry>> submitted = new ArrayList<>();

        BufferedLogChannel<ChatLogEntry> channel = new BufferedLogChannel<>("chat",
                () -> new ArrayList<>(source), ChatLogEntry::getUsername, ChatLogEntry::getMessage,
                entries -> {
                    submitted.add(new ArrayList<>(entries));
                    return submitted.size() == 2
                            ? failed(new PanelUnavailableException("url", 503, "down"))
                            : CompletableFuture.completedFuture(null);
                }, SILENT_LOGGER, 5000, 500);

        flushNow(channel);
        source.clear();
        flushNow(channel);

        assertEquals(3, submitted.size());
        assertEquals(500, submitted.get(0).size());
        assertEquals(200, submitted.get(1).size());
        assertEquals(200, submitted.get(2).size());
        assertEquals("m500", submitted.get(2).get(0).getMessage());
    }

    @Test
    void completionExceptionWrappedValidationFailureDropsBatch() {
        List<ChatLogEntry> source = new ArrayList<>();
        source.add(chat("Notch", "hello"));
        List<List<ChatLogEntry>> submitted = new ArrayList<>();
        CompletableFuture<Void> rejected =
                failed(new CompletionException(new ApiClientException(400, "value must contain")));

        BufferedLogChannel<ChatLogEntry> channel = channel(source, submitted, rejected);
        flushNow(channel);
        source.clear();
        CompletableFuture<Void> second = channel.flush();

        assertEquals(1, submitted.size());
        assertNotNull(second);
    }

    @Test
    void completionExceptionWrappedTransientFailureRebuffers() {
        List<ChatLogEntry> source = new ArrayList<>();
        source.add(chat("Notch", "hello"));
        List<List<ChatLogEntry>> submitted = new ArrayList<>();
        AtomicReference<CompletableFuture<Void>> result = new AtomicReference<>(
                failed(new CompletionException(new PanelUnavailableException("url", 503, "down"))));

        BufferedLogChannel<ChatLogEntry> channel = new BufferedLogChannel<>("chat",
                () -> new ArrayList<>(source), ChatLogEntry::getUsername, ChatLogEntry::getMessage,
                entries -> {
                    submitted.add(new ArrayList<>(entries));
                    return result.get();
                }, SILENT_LOGGER, 5000, 500);

        flushNow(channel);
        source.clear();
        result.set(CompletableFuture.completedFuture(null));
        flushNow(channel);

        assertEquals(2, submitted.size());
        assertEquals("hello", submitted.get(1).get(0).getMessage());
    }

    @Test
    void emptyAfterSanitizeCompletesWithoutSubmitting() {
        List<ChatLogEntry> source = new ArrayList<>();
        source.add(chat("Notch", "  "));
        List<List<ChatLogEntry>> submitted = new ArrayList<>();

        CompletableFuture<Void> flushed = channel(source, submitted, CompletableFuture.completedFuture(null)).flush();

        assertNotNull(flushed);
        assertTrue(submitted.isEmpty());
    }

    @Test
    void drainFailureIsReportedAsAFailedFutureInsteadOfThrown() {
        List<List<ChatLogEntry>> submitted = new ArrayList<>();
        BufferedLogChannel<ChatLogEntry> channel = new BufferedLogChannel<>("chat",
                () -> {
                    throw new IllegalStateException("buffer unavailable");
                },
                ChatLogEntry::getUsername, ChatLogEntry::getMessage,
                entries -> {
                    submitted.add(new ArrayList<>(entries));
                    return CompletableFuture.completedFuture(null);
                }, SILENT_LOGGER, 5000, 500);

        CompletableFuture<Void> flushed = channel.flush();

        assertTrue(flushed.isCompletedExceptionally());
        assertTrue(submitted.isEmpty());
    }

    private static CompletableFuture<Void> failed(Throwable throwable) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        future.completeExceptionally(throwable);
        return future;
    }

    private static void flushNow(BufferedLogChannel<?> channel) {
        assertTrue(channel.flush().isDone());
    }
}
