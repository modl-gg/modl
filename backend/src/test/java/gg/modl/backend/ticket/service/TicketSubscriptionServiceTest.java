package gg.modl.backend.ticket.service;

import static gg.modl.backend.ticket.TicketHarness.NOW;
import static gg.modl.backend.ticket.TicketHarness.reply;
import static gg.modl.backend.ticket.TicketHarness.ticket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.TicketSubscription;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.ticket.TicketHarness;
import gg.modl.backend.ticket.dto.response.SubscriptionUpdateResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.io.TempDir;

class TicketSubscriptionServiceTest {
    private static final String EMAIL = "amy@example.com";

    private final Server server = TestServers.server("server", "admin@example.com");

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void readingAMissingTicketNeverSubscribes(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory, new RetryInjection())) {
            assertFalse(harness.subscriptions().markAsRead(server, EMAIL, "A-404::reply"));

            verify(harness.staff(), never()).subscribe(any(), any(), any());
        }
    }

    @SqlContractTest
    void readingATicketSubscribesAndMarksItRead(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory, new RetryInjection())) {
            harness.insert(ticket("A-1"));
            when(harness.staff().markSubscriptionRead(EMAIL, "A-1", NOW)).thenReturn(true);

            assertTrue(harness.subscriptions().markAsRead(server, EMAIL, "A-1::reply"));

            verify(harness.staff(), atLeastOnce()).subscribe(EMAIL, "A-1", NOW);
        }
    }

    @SqlContractTest
    void updatesCountOnlyRepliesAfterTheReadCursor(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory, new RetryInjection())) {
            harness.insert(ticket("A-1").replies(List.of(
                reply("read").created(NOW.plusSeconds(1)).build(),
                reply("older").created(NOW.plusSeconds(3)).build(),
                reply("newest").created(NOW.plusSeconds(4)).build())));
            when(harness.staff().findSubscriptions(EMAIL))
                .thenReturn(List.of(new TicketSubscription("A-1", NOW, NOW.plusSeconds(2))));

            List<SubscriptionUpdateResponse> updates = harness.subscriptions().getUpdates(server, EMAIL, 10);

            assertEquals(1, updates.size());
            assertEquals("A-1::newest", updates.getFirst().id());
            assertEquals(1, updates.getFirst().additionalCount());
        }
    }

    @SqlContractTest
    void assignedUpdatesSkipTheAssigneesOwnReplies(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory, new RetryInjection())) {
            harness.insert(ticket("A-1").assignedTo(List.of("amy")).replies(List.of(
                reply("theirs").build(),
                reply("own").name("Amy").staff(true).created(NOW.plusSeconds(1)).build())));
            when(harness.staff().findByEmail(EMAIL))
                .thenReturn(Optional.of(Staff.builder().email(EMAIL).username("Amy").build()));

            List<SubscriptionUpdateResponse> updates = harness.subscriptions().getAssignedTicketUpdates(server, EMAIL, 10);

            assertEquals(List.of("A-1::theirs"), updates.stream().map(SubscriptionUpdateResponse::id).toList());
        }
    }
}
