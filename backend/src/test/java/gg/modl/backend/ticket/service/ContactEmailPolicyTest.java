package gg.modl.backend.ticket.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.ticket.service.ContactEmailPolicy.Requirement;
import org.junit.jupiter.api.Test;

class ContactEmailPolicyTest {

    @Test
    void appealsRequireAnEmailOnlyWhenDeliveryIsEnabled() {
        assertEquals(Requirement.REQUIRED, new ContactEmailPolicy(() -> true).forAppeals());
        assertEquals(Requirement.OPTIONAL, new ContactEmailPolicy(() -> false).forAppeals());
    }

    @Test
    void ticketsNeverRequireAnEmailAtTheApi() {
        assertEquals(Requirement.OPTIONAL, new ContactEmailPolicy(() -> true).forTickets());
        assertEquals(Requirement.OPTIONAL, new ContactEmailPolicy(() -> false).forTickets());
    }

    @Test
    void acceptsAndNormalizesValidEmails() {
        ContactEmailPolicy policy = new ContactEmailPolicy(() -> true);

        assertEquals("player@example.com", policy.accept("  Player@Example.COM ", Requirement.REQUIRED));
    }

    @Test
    void blankEmailsAreAbsentUnlessRequired() {
        ContactEmailPolicy policy = new ContactEmailPolicy(() -> false);

        assertNull(policy.accept("   ", Requirement.OPTIONAL));
        assertNull(policy.accept(null, Requirement.OPTIONAL));
        assertThrows(ValidationException.class, () -> policy.accept("", Requirement.REQUIRED));
    }

    @Test
    void invalidEmailsAreRejectedEvenWhenOptional() {
        ContactEmailPolicy policy = new ContactEmailPolicy(() -> false);

        assertThrows(ValidationException.class, () -> policy.accept("not-an-email", Requirement.OPTIONAL));
    }
}
