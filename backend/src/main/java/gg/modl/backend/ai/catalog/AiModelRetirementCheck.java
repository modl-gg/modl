package gg.modl.backend.ai.catalog;

import gg.modl.backend.ai.model.AiConnection;
import gg.modl.backend.ai.model.AiConnections;
import gg.modl.backend.ai.model.AiModelInfo;
import gg.modl.backend.ai.model.AiModelNotice;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.settings.service.AIModerationSettingsService;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiModelRetirementCheck {
    private static final long INITIAL_DELAY_MILLIS = 10 * 60 * 1000L;
    private static final long INTERVAL_MILLIS = 24 * 60 * 60 * 1000L;

    private final AiConnections connections;
    private final AiModelCatalogService catalog;
    private final AIModerationSettingsService moderationSettings;
    private final InstanceContext instanceContext;
    private final AtomicReference<Checked> latest = new AtomicReference<>();

    @Scheduled(initialDelay = INITIAL_DELAY_MILLIS, fixedRate = INTERVAL_MILLIS)
    public void check() {
        Optional<AiConnection> ready = connections.state().ready();
        if (ready.isEmpty() || !moderationSettings.getAIModerationSettings(instanceContext.instance()).isEnableAIReview()) {
            return;
        }
        AiConnection connection = ready.get();
        AiModelCatalog listed = catalog.list(connection, true);
        if (listed.failure() != null) {
            return;
        }
        latest.set(new Checked(connection.fingerprint(), noticeFor(connection, listed)));
    }

    public Optional<AiModelNotice> notice() {
        Checked checked = latest.get();
        if (checked == null) {
            return Optional.empty();
        }
        return connections.state().connection()
            .filter(connection -> connection.fingerprint().equals(checked.fingerprint()))
            .flatMap(connection -> Optional.ofNullable(checked.notice()));
    }

    static @Nullable AiModelNotice noticeFor(AiConnection connection, AiModelCatalog listed) {
        Optional<AiModelInfo> model = listed.models().stream().filter(candidate -> candidate.id().equals(connection.model())).findFirst();
        if (model.isEmpty()) {
            return listed.complete() && !listed.models().isEmpty() ? new AiModelNotice(AiModelNotice.Warning.NOT_LISTED, null) : null;
        }
        if (model.get().deprecated()) {
            return new AiModelNotice(AiModelNotice.Warning.DEPRECATED, model.get().retiresAt());
        }
        if (model.get().retiresAt() != null) {
            return new AiModelNotice(AiModelNotice.Warning.RETIRING, model.get().retiresAt());
        }
        return null;
    }

    private record Checked(String fingerprint, @Nullable AiModelNotice notice) {
    }
}
