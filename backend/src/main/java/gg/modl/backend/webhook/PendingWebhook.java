package gg.modl.backend.webhook;

import java.util.Map;

record PendingWebhook(WebhookEvent event, Map<String, String> variables) {
    PendingWebhook {
        variables = Map.copyOf(variables);
    }
}
