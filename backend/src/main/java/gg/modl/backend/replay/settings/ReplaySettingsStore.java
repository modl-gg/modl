package gg.modl.backend.replay.settings;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.service.CachedSettingsDocument;
import gg.modl.backend.settings.service.SettingsCodec;
import gg.modl.backend.settings.service.SettingsDocumentService;
import gg.modl.backend.settings.service.VersionedSettings;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

@Component
public class ReplaySettingsStore {
    private final CachedSettingsDocument<ReplaySettings> settings;

    public ReplaySettingsStore(SettingsDocumentService documents, UnitOfWork unitOfWork) {
        this.settings = new CachedSettingsDocument<>(SettingsType.REPLAYS,
            SettingsCodec.of(ReplaySettings.class, () -> ReplaySettings.DEFAULTS), documents, unitOfWork);
    }

    public VersionedSettings<ReplaySettings> versioned() {
        return normalized(settings.versioned());
    }

    public VersionedSettings<ReplaySettings> updateExpecting(long expectedVersion, UnaryOperator<ReplaySettings> change) {
        return normalized(settings.updateExpecting(expectedVersion, change));
    }

    private static VersionedSettings<ReplaySettings> normalized(VersionedSettings<ReplaySettings> stored) {
        return new VersionedSettings<>(stored.data().normalized(), stored.version(), stored.updatedAt());
    }
}
