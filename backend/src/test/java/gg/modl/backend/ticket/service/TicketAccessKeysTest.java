package gg.modl.backend.ticket.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.ticket.data.Ticket;
import org.junit.jupiter.api.Test;

class TicketAccessKeysTest {
    private final TicketAccessKeys accessKeys = new TicketAccessKeys(new IdGenerator());

    @Test
    void generatesThirtyTwoRandomBytesAsUnpaddedBase64Url() {
        String first = accessKeys.generate();
        String second = accessKeys.generate();

        assertTrue(first.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(first, second);
    }

    @Test
    void matchesOnlyTheStoredKey() {
        String key = accessKeys.generate();
        Ticket ticket = Ticket.builder().id("SUPPORT-123456").publicAccessKey(key).build();

        assertTrue(accessKeys.matches(ticket, key));
        assertFalse(accessKeys.matches(ticket, key.substring(1)));
        assertFalse(accessKeys.matches(ticket, accessKeys.generate()));
        assertFalse(accessKeys.matches(ticket, ""));
        assertFalse(accessKeys.matches(ticket, null));
    }

    @Test
    void ticketsWithoutAKeyNeverMatch() {
        Ticket ticket = Ticket.builder().id("SUPPORT-123456").build();

        assertFalse(accessKeys.matches(ticket, null));
        assertFalse(accessKeys.matches(ticket, ""));
        assertFalse(accessKeys.matches(ticket, "SUPPORT-123456"));
    }
}
