package gg.modl.minecraft.core.integration.mojang;

import gg.modl.minecraft.core.support.StubConnectionOpener;
import gg.modl.minecraft.core.util.BoundedLookupExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MojangProfileClientTest {

    @Test
    void mojangLookupExecutorThreadsAreBoundedDaemonAndNamed() throws Exception {
        MojangProfileClient client = new MojangProfileClient();
        try {
            Field field = MojangProfileClient.class.getDeclaredField("lookupExecutor");
            field.setAccessible(true);
            BoundedLookupExecutor lookupExecutor = (BoundedLookupExecutor) field.get(client);
            Field poolField = BoundedLookupExecutor.class.getDeclaredField("pool");
            poolField.setAccessible(true);
            ThreadPoolExecutor executor = (ThreadPoolExecutor) poolField.get(lookupExecutor);

            Thread thread = executor.getThreadFactory().newThread(() -> {});

            assertTrue(thread.isDaemon());
            assertTrue(thread.getName().startsWith("modl-web-player-"));
            assertTrue(executor.getMaximumPoolSize() <= 4);
            assertFalse(executor.getQueue().remainingCapacity() == Integer.MAX_VALUE);
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, executor.getRejectedExecutionHandler());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void lookupReturnsResultWithoutBlockingCaller() throws Exception {
        StubConnectionOpener opener = new StubConnectionOpener().respondWith(HttpURLConnection.HTTP_OK,
                "{\"name\":\"byteful\",\"id\":\"00112233445566778899aabbccddeeff\"}");
        MojangProfileClient client = new MojangProfileClient(opener);
        try {
            Thread callerThread = Thread.currentThread();
            CompletableFuture<WebPlayer> lookup = client.get("byteful");
            WebPlayer result = lookup.get(2, TimeUnit.SECONDS);

            assertEquals(1, opener.openCount());
            assertTrue(result.isValid());
            assertEquals("byteful", result.getName());
            assertEquals(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"), result.getUuid());
            assertNotNull(opener.lastConnectionThread());
            assertNotEquals(callerThread, opener.lastConnectionThread());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void aVerifiedDefaultSkinIsKnownSoResettingTheSkinCanLiftASkinBan() throws Exception {
        assertEquals(SkinTextures.DEFAULT_SKIN, skinOfProfileWith("{\"textures\":{}}"));
    }

    @Test
    void aProfileWithoutTexturesLeavesTheSkinUnknown() throws Exception {
        assertNull(skinOfProfileWith("{}"));
    }

    private static String skinOfProfileWith(String decodedTextures) throws Exception {
        String value = Base64.getEncoder().encodeToString(decodedTextures.getBytes(StandardCharsets.UTF_8));
        StubConnectionOpener opener = new StubConnectionOpener().respondWith(HttpURLConnection.HTTP_OK,
                "{\"name\":\"Steve\",\"id\":\"00112233445546778899aabbccddeeff\",\"properties\":[{\"name\":\"textures\",\"value\":\""
                        + value + "\"}]}");
        MojangProfileClient client = new MojangProfileClient(opener);
        try {
            return client.get(UUID.fromString("00112233-4455-4677-8899-aabbccddeeff")).get(2, TimeUnit.SECONDS).getSkin();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void lookupFailureReturnsInvalidGracefully() throws Exception {
        StubConnectionOpener opener = new StubConnectionOpener().respondWith(HttpURLConnection.HTTP_INTERNAL_ERROR, "");
        MojangProfileClient client = new MojangProfileClient(opener);
        try {
            WebPlayer result = client.get("byteful").get(2, TimeUnit.SECONDS);

            assertEquals(1, opener.openCount());
            assertFalse(result.isValid());
        } finally {
            client.shutdown();
        }
    }
}
