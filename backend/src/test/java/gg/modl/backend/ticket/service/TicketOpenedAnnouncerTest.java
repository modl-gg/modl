package gg.modl.backend.ticket.service;

import static gg.modl.backend.ticket.TicketHarness.STAFF_TICKETS;
import static gg.modl.backend.ticket.TicketHarness.ticket;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.ticket.TicketHarness;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.dto.request.CreateTicketRequest;
import gg.modl.backend.ticket.dto.request.MinecraftCreateTicketRequest;
import gg.modl.backend.ticket.dto.request.SubmitTicketFormRequest;
import gg.modl.backend.webhook.WebhookEvent;
import gg.modl.proto.modl.v1.SyncStaffNotification;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class TicketOpenedAnnouncerTest {
    private static final String REPORTER = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String REPORTED = "11111111-2222-3333-4444-555555555555";

    private final Server server = TestServers.server("server", "admin@example.com");

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void anInGameReportAlertsStaffAndTheWebhookOnceAfterCommit(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            Ticket created = harness.minecraftService().createMinecraftTicket(server, gameplayReport());

            verify(harness.webhooks(), times(1)).publish(eq(WebhookEvent.NEW_TICKETS), any());
            SyncStaffNotification notice = pushedNotice(harness);
            assertThat(notice.getId()).isEqualTo("ticket_" + created.getId());
            assertThat(notice.getType()).isEqualTo("TICKET_CREATED");
            assertThat(notice.getMessage()).isEqualTo("Alex: reported Steve on survival (Gameplay)");
            assertThat(notice.getData().getFieldsMap().get("ticketType").getStringValue()).isEqualTo("player");
            assertThat(notice.getData().getFieldsMap().get("reportedPlayer").getStringValue()).isEqualTo("Steve");
            assertThat(notice.getData().getFieldsMap().get("firstReplyContent").getStringValue()).isEqualTo("flying around spawn");
            assertThat(notice.getData().getFieldsMap().get("ticketUrl").getStringValue()).isEqualTo(STAFF_TICKETS + created.getId());
        }
    }

    @SqlContractTest
    void nothingIsAnnouncedBeforeTheUnitCommitsOrWhenItRollsBack(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            MinecraftTicketService service = harness.minecraftService();

            assertThrows(IllegalStateException.class, () -> harness.database().unitOfWork().write(() -> {
                service.createMinecraftTicket(server, gameplayReport());
                verifyNoInteractions(harness.webhooks(), harness.realtime());
                throw new IllegalStateException("rolled back");
            }));

            verifyNoInteractions(harness.webhooks(), harness.realtime());
        }
    }

    @SqlContractTest
    void anUnfinishedTicketIsAnnouncedOnlyWhenItsFormIsSubmitted(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            Ticket unfinished = harness.minecraftService().createUnfinishedMinecraftTicket(server, new MinecraftCreateTicketRequest(
                REPORTER, "Alex", "support", null, null, null, null, null, null, null, "survival", null));
            verifyNoInteractions(harness.webhooks(), harness.realtime());

            harness.ticketService().submitTicketForm(server, unfinished.getId(), new SubmitTicketFormRequest(
                "Lost items", null, Map.of("details", "my chest is gone"), List.of(), null, null, null), false);

            verify(harness.webhooks(), times(1)).publish(eq(WebhookEvent.NEW_TICKETS), any());
            SyncStaffNotification notice = pushedNotice(harness);
            assertThat(notice.getId()).isEqualTo("ticket_" + unfinished.getId());
            assertThat(notice.getMessage()).isEqualTo("Alex: created " + unfinished.getId() + " on survival");
        }
    }

    @SqlContractTest
    void aPanelTicketIsAnnouncedOnlyOnceItIsOpen(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.ticketService().createTicket(server, panelTicket(""));
            harness.ticketService().createUnfinishedTicket(server, panelTicket("Help"));
            verifyNoInteractions(harness.webhooks(), harness.realtime());

            String opened = harness.ticketService().createTicket(server, panelTicket("Help")).id();

            verify(harness.webhooks(), times(1)).publish(eq(WebhookEvent.NEW_TICKETS), any());
            assertThat(pushedNotice(harness).getId()).isEqualTo("ticket_" + opened);
        }
    }

    @SqlContractTest
    void aStaffApplicationReachesTheWebhookButNotTheGame(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("STAFF-1").type(TicketCategory.APPLICATION)
                .status(TicketStatus.UNFINISHED));

            harness.ticketService().submitTicketForm(server, "STAFF-1", new SubmitTicketFormRequest(
                "Application", null, Map.of(), List.of(), null, null, null), false);

            verify(harness.webhooks(), times(1)).publish(eq(WebhookEvent.NEW_TICKETS), any());
            verifyNoInteractions(harness.realtime());
        }
    }

    private static SyncStaffNotification pushedNotice(TicketHarness harness) {
        ArgumentCaptor<List<SyncStaffNotification>> pushed = ArgumentCaptor.captor();
        verify(harness.realtime(), times(1)).pushStaffNotifications(any(), pushed.capture());
        assertThat(pushed.getValue()).hasSize(1);
        return pushed.getValue().getFirst();
    }

    private static MinecraftCreateTicketRequest gameplayReport() {
        return new MinecraftCreateTicketRequest(REPORTER, "Alex", "player", "Player report", "flying around spawn",
            REPORTED, "Steve", List.of(), List.of(), null, "survival", null);
    }

    private static CreateTicketRequest panelTicket(String subject) {
        return new CreateTicketRequest("support", subject, "body", null, "Player", null, null, null, null, null,
            null, null, null, null, null, null);
    }

    private TicketHarness start(DialectTarget target) {
        return TicketHarness.start(target, dataDirectory, new RetryInjection());
    }
}
