package gg.modl.backend.player.dto.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gg.modl.backend.infrastructure.exception.ValidationException;
import org.junit.jupiter.api.Test;

class OnlinePlayerPageTest {
    @Test
    void aMissingLimitAsksForTheLargestPageAndTheFirstPage() {
        OnlinePlayerPage page = OnlinePlayerPage.of(null, null);

        assertNull(page.afterPlayerId());
        assertEquals(OnlinePlayerPage.MAX_SIZE, page.size());
    }

    @Test
    void theLimitIsBounded() {
        assertEquals(1, OnlinePlayerPage.of(null, 0).size());
        assertEquals(OnlinePlayerPage.MAX_SIZE, OnlinePlayerPage.of(null, 100_000).size());
        assertEquals(50, OnlinePlayerPage.of(" ", 50).size());
    }

    @Test
    void aCursorLongerThanAPlayerIdIsRejected() {
        assertEquals("p-1", OnlinePlayerPage.of(" p-1 ", null).afterPlayerId());
        assertThrows(ValidationException.class, () -> OnlinePlayerPage.of("x".repeat(37), null));
    }
}
