package gg.modl.backend.webhook;

import java.util.Map;

public interface WebhookPublisher {
    void publish(WebhookEvent event, Map<String, String> variables);
}
