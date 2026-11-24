package gg.modl.backend.theme.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.service.CachedSettingsDocument;
import gg.modl.backend.settings.service.SettingsCodec;
import gg.modl.backend.settings.service.SettingsDocumentService;
import gg.modl.backend.settings.service.VersionedSettings;
import gg.modl.backend.theme.data.CustomStylesheet;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

@Service
public class CustomCssService {
    private final CachedSettingsDocument<CustomStylesheet> document;
    private final PanelActionAuditor auditor;
    private final AtomicReference<PublishedStylesheet> published = new AtomicReference<>();

    public CustomCssService(SettingsDocumentService documents, UnitOfWork unitOfWork, PanelActionAuditor auditor) {
        this.document = new CachedSettingsDocument<>(SettingsType.PUBLIC_CUSTOM_CSS,
            SettingsCodec.of(CustomStylesheet.class, () -> CustomStylesheet.EMPTY), documents, unitOfWork);
        this.auditor = auditor;
    }

    public VersionedSettings<CustomStylesheet> versioned() {
        return document.versioned();
    }

    public PublishedStylesheet published() {
        CustomStylesheet current = document.current();
        PublishedStylesheet cached = published.get();
        if (cached != null && cached.stylesheet().equals(current)) {
            return cached;
        }
        PublishedStylesheet next = new PublishedStylesheet(current, current.revision());
        published.set(next);
        return next;
    }

    public record PublishedStylesheet(CustomStylesheet stylesheet, String revision) {
    }

    public VersionedSettings<CustomStylesheet> change(Server server, String actorEmail, long expectedVersion, String css) {
        CustomStylesheet requested = CustomCssPolicy.accepted(css);
        CustomStylesheet previous = document.current();
        VersionedSettings<CustomStylesheet> saved = document.updateExpecting(expectedVersion, stored -> requested);
        if (!saved.data().equals(previous)) {
            auditor.recordStaffAction(server, actorEmail, auditDescription(saved.data()));
        }
        return saved;
    }

    private static String auditDescription(CustomStylesheet saved) {
        return saved.empty()
            ? "Removed the custom CSS of the public site"
            : "Changed the custom CSS of the public site (" + saved.bytes().length + " bytes)";
    }
}
