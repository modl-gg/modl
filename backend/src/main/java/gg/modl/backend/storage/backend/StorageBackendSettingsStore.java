package gg.modl.backend.storage.backend;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.service.CachedSettingsDocument;
import gg.modl.backend.settings.service.SettingsCodec;
import gg.modl.backend.settings.service.SettingsDocumentService;
import gg.modl.backend.storage.StorageMoves;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

@Component
public class StorageBackendSettingsStore implements StorageMoves {
    private final CachedSettingsDocument<StorageBackendSettings> document;

    public StorageBackendSettingsStore(SettingsDocumentService documents, UnitOfWork unitOfWork) {
        this.document = new CachedSettingsDocument<>(SettingsType.STORAGE_BACKEND,
            SettingsCodec.of(StorageBackendSettings.class, () -> StorageBackendSettings.DEFAULTS), documents, unitOfWork);
    }

    public StorageBackendSettings current() {
        return document.current();
    }

    @Override
    public boolean isMovingFiles() {
        return current().migrationTarget().isPresent();
    }

    StorageBackendSettings update(UnaryOperator<StorageBackendSettings> change) {
        return document.update(change);
    }
}
