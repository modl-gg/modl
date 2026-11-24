package gg.modl.backend.webhook;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

record WebhookMessage(List<PendingWebhook> items, Map<WebhookEvent, Integer> notSent) {
    WebhookMessage {
        items = List.copyOf(items);
        notSent = notSent.isEmpty() ? Map.of() : new EnumMap<>(notSent);
    }

    boolean isEmpty() {
        return items.isEmpty() && notSent.isEmpty();
    }
}
