package gg.modl.minecraft.core.imports.support;

import gg.modl.minecraft.core.imports.source.SourceException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerKeySetTest {
    private static final String FIRST = "11111111-1111-4111-8111-111111111111";
    private static final String SECOND = "22222222-2222-4222-8222-222222222222";

    @Test
    void keysAreKeptOnceInAStableOrder() throws Exception {
        PlayerKeySet keys = new PlayerKeySet();
        keys.add(SECOND);
        keys.add(FIRST);
        keys.add(SECOND);

        assertEquals(2, keys.size());
        List<String> read = new ArrayList<>();
        keys.forEach(read::add);
        assertEquals(Arrays.asList(FIRST, SECOND), read);
    }

    @Test
    void duplicatesBeyondTheBufferAreCompactedInsteadOfRefused() throws Exception {
        PlayerKeySet keys = new PlayerKeySet(3);
        for (int round = 0; round < 5_000; round++) keys.add(round % 2 == 0 ? FIRST : SECOND);

        assertEquals(2, keys.size());
    }

    @Test
    void moreDistinctPlayersThanTheLimitAreRefused() {
        PlayerKeySet keys = new PlayerKeySet(3);
        for (int index = 0; index < 5_000; index++) keys.add(new UUID(index, index).toString());

        SourceException refused = assertThrows(SourceException.class, keys::size);
        assertEquals("import.too_many_players", refused.getMessageKey());
    }

    @Test
    void manyDuplicatesNearTheLimitStayFast() throws Exception {
        int limit = 20_000;
        PlayerKeySet keys = new PlayerKeySet(limit);
        long started = System.nanoTime();
        for (int round = 0; round < 50; round++) {
            for (int index = 0; index < limit - 1; index++) keys.add(new UUID(index, index).toString());
        }

        assertEquals(limit - 1, keys.size());
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(20));
    }
}
