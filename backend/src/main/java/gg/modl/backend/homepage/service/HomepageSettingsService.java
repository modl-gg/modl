package gg.modl.backend.homepage.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.homepage.data.HomepageCardEdit;
import gg.modl.backend.homepage.data.HomepageLayout;
import gg.modl.backend.homepage.data.PublishedHomepage;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.connection.OnlinePlayerCount;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.service.CachedSettingsDocument;
import gg.modl.backend.settings.service.FieldChanges;
import gg.modl.backend.settings.service.SettingsCodec;
import gg.modl.backend.settings.service.SettingsDocumentService;
import gg.modl.backend.settings.service.VersionedSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.springframework.stereotype.Service;

@Service
public class HomepageSettingsService {
    private static final FieldChanges<HomepageLayout> CHANGES = FieldChanges.<HomepageLayout>summarizedAs("Changed the homepage: ")
        .compare("tagline", HomepageLayout::tagline)
        .compare("server address", HomepageLayout::serverAddress)
        .compare("player count", HomepageLayout::showPlayerCount)
        .compare("footer links", HomepageLayout::footerLinks)
        .build();

    private static final String CARDS_LABEL = "cards";

    private final CachedSettingsDocument<HomepageLayout> document;
    private final UnitOfWork unitOfWork;
    private final HomepageCardService cardService;
    private final PanelActionAuditor auditor;
    private final OnlinePlayerCount onlinePlayers;

    public HomepageSettingsService(SettingsDocumentService documents, UnitOfWork unitOfWork, PanelActionAuditor auditor,
                                   OnlinePlayerCount onlinePlayers, HomepageCardService cardService) {
        this.document = new CachedSettingsDocument<>(SettingsType.PUBLIC_HOMEPAGE,
            SettingsCodec.of(HomepageLayout.class, () -> HomepageLayout.DEFAULT), documents, unitOfWork);
        this.unitOfWork = unitOfWork;
        this.cardService = cardService;
        this.auditor = auditor;
        this.onlinePlayers = onlinePlayers;
    }

    public HomepageLayout current() {
        return document.current();
    }

    public PublishedHomepage published() {
        HomepageLayout settings = current();
        return new PublishedHomepage(settings, settings.showPlayerCount() ? OptionalInt.of(onlinePlayers.total()) : OptionalInt.empty());
    }

    public VersionedSettings<HomepageLayout> versioned() {
        return document.versioned();
    }

    public VersionedSettings<HomepageLayout> change(Server server, String actorEmail, long expectedVersion,
                                                      HomepageLayout requested, List<HomepageCardEdit> cards) {
        HomepageLayout validated = HomepageSettingsValues.validated(requested);
        List<HomepageCardEdit> validCards = HomepageCardValues.validated(cards);
        HomepageLayout previous = current();
        SavedHomepage saved = unitOfWork.write(() -> {
            boolean cardsChanged = cardService.replaceWith(validCards);
            VersionedSettings<HomepageLayout> settings = document.updateExpecting(expectedVersion, stored -> validated.withCardsRevision(
                cardsChanged ? stored.cardsRevision() + 1 : stored.cardsRevision()));
            return new SavedHomepage(settings, cardsChanged);
        });
        List<String> changed = new ArrayList<>(CHANGES.between(previous, saved.settings().data()));
        if (saved.cardsChanged()) {
            changed.add(CARDS_LABEL);
        }
        CHANGES.summarize(changed).ifPresent(summary -> auditor.recordStaffAction(server, actorEmail, summary));
        return saved.settings();
    }

    private record SavedHomepage(VersionedSettings<HomepageLayout> settings, boolean cardsChanged) {
    }
}
