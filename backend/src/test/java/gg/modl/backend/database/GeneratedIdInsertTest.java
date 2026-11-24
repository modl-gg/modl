package gg.modl.backend.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class GeneratedIdInsertTest {
    @Test
    void aFreeIdIsInsertedOnTheFirstAttempt() {
        List<String> attempted = new ArrayList<>();

        String id = GeneratedIdInsert.insert(() -> "free", recording(attempted, "free"), GeneratedIdInsert.DEFAULT_ATTEMPTS);

        assertEquals("free", id);
        assertEquals(List.of("free"), attempted);
    }

    @Test
    void aCollidingIdIsReplacedByAFreshlyGeneratedOne() {
        Iterator<String> ids = List.of("taken", "also-taken", "free").iterator();
        List<String> attempted = new ArrayList<>();

        String id = GeneratedIdInsert.insert(ids::next, recording(attempted, "free"), GeneratedIdInsert.DEFAULT_ATTEMPTS);

        assertEquals("free", id);
        assertEquals(List.of("taken", "also-taken", "free"), attempted);
    }

    @Test
    void theInsertGivesUpAfterTheConfiguredNumberOfAttempts() {
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(ConcurrentUpdateException.class, () -> GeneratedIdInsert.insert(EntityIds::next, candidate -> {
            attempts.incrementAndGet();
            return false;
        }, 4));

        assertEquals(4, attempts.get());
    }

    private static Predicate<String> recording(List<String> attempted, String freeId) {
        return candidate -> {
            attempted.add(candidate);
            return candidate.equals(freeId);
        };
    }
}
