package gg.modl.backend.webhook;

import gg.modl.backend.audit.data.AuditLog;
import gg.modl.backend.audit.service.AuditMetadata;
import gg.modl.backend.log.repository.AuditLogObserver;
import gg.modl.backend.log.service.AuditActor;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuditLogWebhook implements AuditLogObserver {
    private final WebhookPublisher publisher;

    @Override
    public void committed(AuditLog entry) {
        publisher.publish(WebhookEvent.AUDIT_LOGS, variablesOf(entry));
    }

    static Map<String, String> variablesOf(AuditLog entry) {
        Map<String, Object> metadata = entry.getMetadata() != null ? entry.getMetadata() : Map.of();
        Map<String, String> variables = new HashMap<>();
        variables.put("user", AuditActor.displayNameFor(entry.getSource()));
        variables.put("action", textOf(entry.getDescription()));
        variables.put("target", textOf(metadata.get(AuditMetadata.PLAYER_NAME)));
        variables.put("details", textOf(metadata.get(AuditMetadata.PUNISHMENT_TYPE)));
        return variables;
    }

    private static String textOf(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
