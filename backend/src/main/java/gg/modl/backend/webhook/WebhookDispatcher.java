package gg.modl.backend.webhook;

import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.settings.data.WebhookSettings;
import gg.modl.backend.settings.service.WebhookSettingsService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class WebhookDispatcher implements WebhookPublisher {
    static final long FLUSH_INTERVAL_MILLIS = 5_000;
    static final int MAX_PENDING = 100;

    private final WebhookSettingsService webhookSettingsService;
    private final DiscordWebhookDelivery delivery;
    private final InstanceContext instanceContext;
    private final Executor executor;
    private final ArrayDeque<PendingWebhook> pending = new ArrayDeque<>();
    private final Map<WebhookEvent, Integer> dropped = new EnumMap<>(WebhookEvent.class);
    private final AtomicBoolean delivering = new AtomicBoolean();

    public WebhookDispatcher(
        WebhookSettingsService webhookSettingsService,
        DiscordWebhookDelivery delivery,
        InstanceContext instanceContext,
        @Qualifier("applicationTaskExecutor") Executor executor
    ) {
        this.webhookSettingsService = webhookSettingsService;
        this.delivery = delivery;
        this.instanceContext = instanceContext;
        this.executor = executor;
    }

    @Override
    public void publish(WebhookEvent event, Map<String, String> variables) {
        PendingWebhook item = new PendingWebhook(event, variables);
        synchronized (pending) {
            if (pending.size() < MAX_PENDING) {
                pending.addLast(item);
            } else {
                dropped.merge(event, 1, Integer::sum);
            }
        }
    }

    @Scheduled(fixedDelay = FLUSH_INTERVAL_MILLIS, initialDelay = FLUSH_INTERVAL_MILLIS)
    public void flush() {
        if (!hasWork() || !delivering.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::deliverNextMessage);
        } catch (RejectedExecutionException rejected) {
            delivering.set(false);
            log.warn("The Discord webhook could not be scheduled; its events wait for the next window");
        }
    }

    private void deliverNextMessage() {
        try {
            WebhookSettings settings = webhookSettingsService.getWebhookSettings(instanceContext.instance());
            WebhookMessage message = drain(settings);
            if (!message.isEmpty()) {
                delivery.send(settings, message);
            }
        } catch (RuntimeException failure) {
            log.warn("The Discord webhook failed ({})", failure.getClass().getSimpleName());
        } finally {
            delivering.set(false);
        }
    }

    private boolean hasWork() {
        synchronized (pending) {
            return !pending.isEmpty() || !dropped.isEmpty();
        }
    }

    private WebhookMessage drain(WebhookSettings settings) {
        synchronized (pending) {
            List<PendingWebhook> items = new ArrayList<>();
            while (items.size() < DiscordWebhookDelivery.MAX_ITEMS_PER_MESSAGE && !pending.isEmpty()) {
                PendingWebhook next = pending.pollFirst();
                if (delivery.delivers(settings, next.event())) {
                    items.add(next);
                }
            }
            Map<WebhookEvent, Integer> notSent = new EnumMap<>(WebhookEvent.class);
            dropped.forEach((event, count) -> {
                if (delivery.delivers(settings, event)) {
                    notSent.put(event, count);
                }
            });
            dropped.clear();
            return new WebhookMessage(items, notSent);
        }
    }
}
