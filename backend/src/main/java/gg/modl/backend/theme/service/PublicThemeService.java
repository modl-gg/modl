package gg.modl.backend.theme.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.service.CachedSettingsDocument;
import gg.modl.backend.settings.service.SettingsCodec;
import gg.modl.backend.settings.service.SettingsDocumentService;
import gg.modl.backend.settings.service.VersionedSettings;
import gg.modl.backend.storage.StorageOrigins;
import gg.modl.backend.theme.data.PublicSiteTheme;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PublicThemeService {
    private static final String AUDIT_PREFIX = "Changed the public site theme: ";
    private static final String AUDIT_SEPARATOR = ", ";

    private final CachedSettingsDocument<PublicSiteTheme> document;
    private final PanelActionAuditor auditor;
    private final StorageOrigins storageOrigins;

    public PublicThemeService(SettingsDocumentService documents, UnitOfWork unitOfWork, PanelActionAuditor auditor,
                              StorageOrigins storageOrigins) {
        this.document = new CachedSettingsDocument<>(SettingsType.PUBLIC_THEME,
            SettingsCodec.of(PublicSiteTheme.class, () -> PublicSiteTheme.DEFAULT), documents, unitOfWork);
        this.auditor = auditor;
        this.storageOrigins = storageOrigins;
    }

    public PublicSiteTheme current() {
        return document.current();
    }

    public VersionedSettings<PublicSiteTheme> versioned() {
        return document.versioned();
    }

    public VersionedSettings<PublicSiteTheme> change(Server server, String actorEmail, long expectedVersion,
                                                     PublicSiteTheme requested) {
        PublicSiteTheme validated = PublicThemeValues.validated(requested, storageOrigins.origins());
        PublicSiteTheme previous = current();
        VersionedSettings<PublicSiteTheme> saved = document.updateExpecting(expectedVersion, stored -> validated);
        List<String> changed = PublicThemeChanges.between(previous, saved.data());
        if (!changed.isEmpty()) {
            auditor.recordStaffAction(server, actorEmail, AUDIT_PREFIX + String.join(AUDIT_SEPARATOR, changed));
        }
        return saved;
    }
}
