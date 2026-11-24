package gg.modl.backend.ticket.service;

import static gg.modl.backend.ticket.TicketHarness.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.email.EmailHTMLTemplate;
import gg.modl.backend.email.EmailService;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.repository.PlayerNotification;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.service.SyncProtoFactory;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.ticket.TicketHarness;
import gg.modl.backend.ticket.config.NotificationExpiry;
import gg.modl.backend.ticket.data.TicketNote;
import gg.modl.backend.ticket.data.TicketReply;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class TicketNotificationServiceTest {
    private static final String CREATOR = "11111111-2222-3333-4444-555555555555";

    @Test
    void anInGameNotificationExpiresAfterTheConfiguredDays() {
        PlayerRepository players = mock(PlayerRepository.class);
        Player player = Player.builder().id("player-1").minecraftUuid(UUID.fromString(CREATOR)).build();
        when(players.findByMinecraftUuid(CREATOR)).thenReturn(Optional.of(player));
        UnitOfWork unitOfWork = mock(UnitOfWork.class);
        Mockito.doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(unitOfWork).write(any(Runnable.class));
        PublicUrls urls = mock(PublicUrls.class);
        when(urls.ticket(any(), any())).thenReturn("https://panel.example.com/ticket/A-1");
        TicketNotificationService service = new TicketNotificationService(players, unitOfWork, new MutableClock(NOW),
            mock(EmailService.class), urls, new TicketPublicLinks(urls), mock(RealtimeEventPublisher.class),
            new SyncProtoFactory(), NotificationExpiry.ofDays(90));

        service.notifyTicketClosed(TestServers.server("server", "admin@example.com"),
            TicketHarness.ticket("A-1").creatorUuid(CREATOR).build());

        ArgumentCaptor<PlayerNotification> stored = ArgumentCaptor.forClass(PlayerNotification.class);
        verify(players).addNotification(eq("player-1"), stored.capture());
        assertEquals(NOW, stored.getValue().createdAt());
        assertEquals(NOW.plus(Duration.ofDays(90)), stored.getValue().expiresAt());
        assertEquals("TICKET_CLOSED", stored.getValue().payload().get("type"));
        assertEquals(stored.getValue().id(), stored.getValue().payload().get("id"));
    }

    @Test
    void theEmailedTranscriptLeavesStaffNotesOut() {
        EmailService emails = mock(EmailService.class);
        List<TicketReply> replies = List.of(
            TicketHarness.reply("1").content("my question").build(),
            TicketHarness.reply("3").type("staff").staff(true).content("our answer").build());
        List<TicketNote> notes = List.of(TicketNote.builder().text("informant is Alex").issuerName("Mod").date(NOW).build());

        service(mock(PlayerRepository.class), emails, mock(RealtimeEventPublisher.class)).notifyTicketClosed(
            TestServers.server("server", "admin@example.com"),
            TicketHarness.ticket("A-1").data(Map.of("creatorEmail", "player@example.com")).replies(replies).notes(notes).build());

        ArgumentCaptor<EmailHTMLTemplate.HTMLEmail> sent = ArgumentCaptor.forClass(EmailHTMLTemplate.HTMLEmail.class);
        verify(emails).send(eq("player@example.com"), sent.capture());
        String transcript = sent.getValue().body();
        assertTrue(transcript.contains("my question") && transcript.contains("our answer"));
        assertFalse(transcript.contains("informant is Alex"));
    }

    private static TicketNotificationService service(PlayerRepository players, EmailService emails,
                                                     RealtimeEventPublisher realtime) {
        PublicUrls urls = mock(PublicUrls.class);
        when(urls.ticket(any(), any())).thenReturn("https://panel.example.com/ticket/A-1");
        return new TicketNotificationService(players, mock(UnitOfWork.class), new MutableClock(NOW), emails, urls,
            new TicketPublicLinks(urls), realtime, new SyncProtoFactory(), NotificationExpiry.ofDays(90));
    }
}
