package gg.modl.backend.theme.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.service.CachedSettingsDocument;
import gg.modl.backend.settings.service.FieldChanges;
import gg.modl.backend.settings.service.SettingsCodec;
import gg.modl.backend.settings.service.SettingsDocumentService;
import gg.modl.backend.settings.service.VersionedSettings;
import gg.modl.backend.storage.StorageOrigins;
import gg.modl.backend.theme.data.PublicSiteTheme;
import org.springframework.stereotype.Service;

@Service
public class PublicThemeService {
    private static final FieldChanges<PublicSiteTheme> CHANGES = FieldChanges.<PublicSiteTheme>summarizedAs(
            "Changed the public site theme: ")
        .compare("primary color", PublicSiteTheme::primaryColor)
        .compare("accent color", PublicSiteTheme::accentColor)
        .compare("background color", PublicSiteTheme::backgroundColor)
        .compare("background image", PublicSiteTheme::backgroundImageUrl)
        .compare("font", PublicSiteTheme::font)
        .compare("corner radius", PublicSiteTheme::radius)
        .compare("default color scheme", PublicSiteTheme::colorScheme)
        .build();

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
        CHANGES.summary(previous, saved.data()).ifPresent(summary -> auditor.recordStaffAction(server, actorEmail, summary));
        return saved;
    }
}
