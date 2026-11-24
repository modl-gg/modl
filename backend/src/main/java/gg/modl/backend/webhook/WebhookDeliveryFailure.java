package gg.modl.backend.webhook;

import java.time.Instant;

public record WebhookDeliveryFailure(Instant failedAt, WebhookEvent event, WebhookFailureReason reason, int httpStatus) {
}
