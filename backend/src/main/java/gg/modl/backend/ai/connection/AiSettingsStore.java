package gg.modl.backend.ai.connection;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.service.CachedSettingsDocument;
import gg.modl.backend.settings.service.SettingsCodec;
import gg.modl.backend.settings.service.SettingsDocumentService;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

@Component
public class AiSettingsStore {
    private static final SettingsCodec<AiProviderSettings> CODEC = SettingsCodec.of(AiProviderSettings.class, () -> AiProviderSettings.EMPTY);

    private final CachedSettingsDocument<AiProviderSettings> document;

    public AiSettingsStore(SettingsDocumentService documents, UnitOfWork unitOfWork) {
        this.document = new CachedSettingsDocument<>(SettingsType.AI_PROVIDER, CODEC, documents, unitOfWork);
    }

    public AiProviderSettings current() {
        return document.current();
    }

    public AiProviderSettings update(UnaryOperator<AiProviderSettings> change) {
        return document.update(change);
    }
}
