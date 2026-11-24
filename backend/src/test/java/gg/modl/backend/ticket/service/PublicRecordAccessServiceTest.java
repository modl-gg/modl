package gg.modl.backend.ticket.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.service.PublicRecordAccessService.Access;
import gg.modl.backend.ticket.service.PublicRecordAccessService.AccessResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublicRecordAccessServiceTest {
    private static final String ACCESS_KEY = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";

    private final Server server = TestServers.server("Demo", "admin@example.com");
    private final TicketEmailVerificationService verificationService = mock(TicketEmailVerificationService.class);

    @Test
    void theStoredAccessKeyGrantsAccessWithoutEmailVerification() {
        AccessResult result = service(true).authorize(server, ticket(Map.of()), ACCESS_KEY);

        assertEquals(Access.GRANTED, result.access());
        assertFalse(result.emailVerified());
        verify(verificationService, never()).validateToken(any(), any(), any());
    }

    @Test
    void aValidEmailVerificationTokenGrantsVerifiedAccess() {
        when(verificationService.validateToken(server, "SUPPORT-100001", "verification-token")).thenReturn(true);

        AccessResult result = service(true).authorize(server, ticket(Map.of()), "verification-token");

        assertEquals(Access.GRANTED, result.access());
        assertTrue(result.emailVerified());
    }

    @Test
    void aBareTicketIdIsNeverEnoughInAnyMode() {
        for (boolean deliveryEnabled : new boolean[] {true, false}) {
            AccessResult withoutToken = service(deliveryEnabled).authorize(server, ticket(Map.of()), null);
            AccessResult withId = service(deliveryEnabled).authorize(server, ticket(Map.of()), "SUPPORT-100001");

            assertEquals(Access.TOKEN_REQUIRED, withoutToken.access());
            assertEquals(Access.TOKEN_REQUIRED, withId.access());
        }
    }

    @Test
    void aWrongOrPartialKeyIsRejected() {
        AccessResult partial = service(true).authorize(server, ticket(Map.of()), ACCESS_KEY.substring(0, 42));
        AccessResult other = service(true).authorize(server, ticket(Map.of()), new IdGenerator().generateToken());

        assertEquals(Access.TOKEN_REQUIRED, partial.access());
        assertEquals(Access.TOKEN_REQUIRED, other.access());
    }

    @Test
    void ticketsWithoutAStoredKeyRequireEmailVerification() {
        Ticket legacy = Ticket.builder().id("SUPPORT-100001").build();

        AccessResult result = service(true).authorize(server, legacy, "");

        assertEquals(Access.TOKEN_REQUIRED, result.access());
    }

    @Test
    void theEmailHintIsOnlyOfferedWhenDeliveryIsEnabled() {
        Ticket withEmail = ticket(Map.of("creatorEmail", "player@example.com"));

        AccessResult deliveryOn = service(true).authorize(server, withEmail, null);
        AccessResult deliveryOff = service(false).authorize(server, withEmail, null);

        assertEquals("p***@example.com", deliveryOn.emailHint());
        assertNull(deliveryOff.emailHint());
    }

    @Test
    void hiddenAndMissingRecordsAreNotFound() {
        Ticket hidden = Ticket.builder().id("SUPPORT-100001").publicAccessKey(ACCESS_KEY).hidden(true).build();

        assertEquals(Access.NOT_FOUND, service(true).authorize(server, hidden, ACCESS_KEY).access());
        assertEquals(Access.NOT_FOUND, service(true).authorize(server, null, ACCESS_KEY).access());
    }

    @Test
    void anOptedInTicketIsNotReadableWithTheLinkAlone() {
        Ticket opted = optedIn(Map.of("creatorEmail", "player@example.com"));

        AccessResult result = service(true).authorize(server, opted, ACCESS_KEY);

        assertEquals(Access.TOKEN_REQUIRED, result.access());
        assertEquals("p***@example.com", result.emailHint());
    }

    @Test
    void anOptedInTicketOpensWithAVerificationToken() {
        when(verificationService.validateToken(server, "SUPPORT-100001", "verification-token")).thenReturn(true);

        AccessResult result = service(true)
            .authorize(server, optedIn(Map.of("creatorEmail", "player@example.com")), "verification-token");

        assertEquals(Access.GRANTED, result.access());
        assertTrue(result.emailVerified());
    }

    @Test
    void thePlayerIsNotLockedOutWithoutEmailDelivery() {
        AccessResult result = service(false)
            .authorize(server, optedIn(Map.of("creatorEmail", "player@example.com")), ACCESS_KEY);

        assertEquals(Access.GRANTED, result.access());
    }

    @Test
    void thePlayerIsNotLockedOutWithoutADeliverableEmail() {
        assertEquals(Access.GRANTED, service(true).authorize(server, optedIn(Map.of()), ACCESS_KEY).access());
        assertEquals(Access.GRANTED,
            service(true).authorize(server, optedIn(Map.of("creatorEmail", "not-an-email")), ACCESS_KEY).access());
    }

    private static Ticket optedIn(Map<String, Object> data) {
        Ticket opted = ticket(data);
        opted.setEmailAuthEnabled(true);
        return opted;
    }

    private PublicRecordAccessService service(boolean deliveryEnabled) {
        return new PublicRecordAccessService(new TicketAccessKeys(new IdGenerator()), verificationService, () -> deliveryEnabled);
    }

    private static Ticket ticket(Map<String, Object> data) {
        return Ticket.builder()
            .id("SUPPORT-100001")
            .publicAccessKey(ACCESS_KEY)
            .data(data)
            .build();
    }
}
