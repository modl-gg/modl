package gg.modl.backend.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.WebhookSettings;
import gg.modl.backend.settings.service.WebhookSettingsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WebhookDispatcherTest {
    private final WebhookSettingsService settingsService = mock(WebhookSettingsService.class);
    private final DiscordWebhookDelivery delivery = mock(DiscordWebhookDelivery.class);
    private final InstanceContext instanceContext = mock(InstanceContext.class);
    private final List<Runnable> scheduled = new ArrayList<>();
    private final WebhookSettings settings = WebhookSettings.builder().build();
    private final WebhookDispatcher dispatcher = new WebhookDispatcher(settingsService, delivery, instanceContext, scheduled::add);

    WebhookDispatcherTest() {
        Server server = mock(Server.class);
        when(instanceContext.instance()).thenReturn(server);
        when(settingsService.getWebhookSettings(server)).thenReturn(settings);
        when(delivery.delivers(eq(settings), any())).thenReturn(true);
    }

    @Test
    void publishingNeverTouchesTheNetworkOrTheDatabase() {
        IntStream.range(0, 50).forEach(index -> dispatcher.publish(WebhookEvent.NEW_TICKETS, Map.of("id", "T-" + index)));

        verify(delivery, never()).send(any(), any());
        verify(settingsService, never()).getWebhookSettings(any());
    }

    @Test
    void ticketsPunishmentsAndAuditEntriesShareOneRateLimitedMessagePerWindow() {
        IntStream.range(0, 5).forEach(index -> {
            dispatcher.publish(WebhookEvent.NEW_TICKETS, Map.of("id", "T-" + index));
            dispatcher.publish(WebhookEvent.NEW_PUNISHMENTS, Map.of("id", "P-" + index));
            dispatcher.publish(WebhookEvent.AUDIT_LOGS, Map.of("action", "Entry " + index));
        });

        dispatcher.flush();
        dispatcher.flush();
        runScheduled();
        List<WebhookMessage> first = sentMessages(1);
        dispatcher.flush();
        runScheduled();
        List<WebhookMessage> both = sentMessages(2);

        assertThat(first.getFirst().items()).hasSize(DiscordWebhookDelivery.MAX_ITEMS_PER_MESSAGE);
        assertThat(both.getLast().items()).hasSize(15 - DiscordWebhookDelivery.MAX_ITEMS_PER_MESSAGE);
        assertThat(both.getFirst().items()).extracting(PendingWebhook::event)
            .contains(WebhookEvent.NEW_TICKETS, WebhookEvent.NEW_PUNISHMENTS, WebhookEvent.AUDIT_LOGS);
    }

    @Test
    void eventsBeyondTheQueueAreCountedPerEventAndReportedInTheNextMessage() {
        IntStream.range(0, WebhookDispatcher.MAX_PENDING + 3)
            .forEach(index -> dispatcher.publish(WebhookEvent.NEW_PUNISHMENTS, Map.of("id", "P-" + index)));

        dispatcher.flush();
        runScheduled();

        assertThat(sentMessages(1).getFirst().notSent()).containsExactlyEntriesOf(Map.of(WebhookEvent.NEW_PUNISHMENTS, 3));
    }

    @Test
    void eventsThatAreSwitchedOffAreDiscardedWithoutAMessage() {
        when(delivery.delivers(settings, WebhookEvent.AUDIT_LOGS)).thenReturn(false);
        dispatcher.publish(WebhookEvent.AUDIT_LOGS, Map.of("action", "Entry"));

        dispatcher.flush();
        runScheduled();
        dispatcher.flush();

        verify(delivery, never()).send(any(), any());
        assertThat(scheduled).isEmpty();
    }

    @Test
    void aSlowDeliveryIsNeverOverlappedByTheNextWindow() {
        dispatcher.publish(WebhookEvent.NEW_TICKETS, Map.of("id", "T-1"));
        dispatcher.flush();
        dispatcher.publish(WebhookEvent.NEW_TICKETS, Map.of("id", "T-2"));
        dispatcher.flush();

        assertThat(scheduled).hasSize(1);
        runScheduled();
        assertThat(sentMessages(1).getFirst().items()).hasSize(2);
        dispatcher.flush();
        assertThat(scheduled).isEmpty();
    }

    @Test
    void aFailedDeliveryDoesNotStopLaterWindows() {
        doThrow(new IllegalStateException("down")).when(delivery).send(any(), any());
        dispatcher.publish(WebhookEvent.NEW_TICKETS, Map.of("id", "T-1"));
        dispatcher.flush();
        runScheduled();

        dispatcher.publish(WebhookEvent.NEW_TICKETS, Map.of("id", "T-2"));
        dispatcher.flush();

        assertThat(scheduled).hasSize(1);
    }

    private List<WebhookMessage> sentMessages(int expected) {
        ArgumentCaptor<WebhookMessage> messages = ArgumentCaptor.forClass(WebhookMessage.class);
        verify(delivery, times(expected)).send(eq(settings), messages.capture());
        return messages.getAllValues();
    }

    private void runScheduled() {
        List<Runnable> tasks = List.copyOf(scheduled);
        scheduled.clear();
        tasks.forEach(Runnable::run);
    }
}
