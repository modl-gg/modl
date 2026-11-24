package gg.modl.backend.webhook;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class WebhookDeliveryStatus {
    private final AtomicReference<WebhookDeliveryFailure> lastFailure = new AtomicReference<>();

    public void failed(WebhookDeliveryFailure failure) {
        lastFailure.set(failure);
    }

    public Optional<WebhookDeliveryFailure> lastFailure() {
        return Optional.ofNullable(lastFailure.get());
    }
}
