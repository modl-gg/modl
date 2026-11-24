package gg.modl.backend.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import gg.modl.backend.audit.data.AuditLog;
import gg.modl.backend.audit.service.AuditMetadata;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditLogWebhookTest {
    private final WebhookPublisher publisher = mock(WebhookPublisher.class);
    private final AuditLogWebhook webhook = new AuditLogWebhook(publisher);

    @Test
    @SuppressWarnings("unchecked")
    void aCommittedEntryIsPublishedThroughTheSharedDispatcher() {
        webhook.committed(AuditLog.builder().source("Mod").description("Ban issued for Steve")
            .metadata(Map.of(AuditMetadata.PLAYER_NAME, "Steve", AuditMetadata.PUNISHMENT_TYPE, "Ban")).build());

        ArgumentCaptor<Map<String, String>> variables = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publish(eq(WebhookEvent.AUDIT_LOGS), variables.capture());
        assertThat(variables.getValue()).containsEntry("user", "Mod").containsEntry("action", "Ban issued for Steve")
            .containsEntry("target", "Steve").containsEntry("details", "Ban");
    }

    @Test
    void anEmailSourceNeverReachesDiscord() {
        assertThat(AuditLogWebhook.variablesOf(AuditLog.builder().source("owner@example.com").build()))
            .containsEntry("user", "Unknown Staff");
    }
}
