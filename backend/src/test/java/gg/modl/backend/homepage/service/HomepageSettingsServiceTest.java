package gg.modl.backend.homepage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.StaleClientVersionException;
import gg.modl.backend.homepage.data.FooterLink;
import gg.modl.backend.homepage.data.FooterLinkKind;
import gg.modl.backend.homepage.data.HomepageCardAction;
import gg.modl.backend.homepage.data.HomepageCardEdit;
import gg.modl.backend.homepage.data.HomepageLayout;
import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.connection.OnlinePlayerCount;
import gg.modl.backend.settings.service.VersionedSettings;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HomepageSettingsServiceTest {
    private static final String OWNER = "owner@example.com";
    private static final FooterLink DISCORD = new FooterLink("Discord", "https://discord.gg/example", FooterLinkKind.DISCORD);

    @TempDir
    Path dataDirectory;

    private InstanceTestKit kit;
    private PanelActionAuditor auditor;
    private OnlinePlayerCount onlinePlayers;
    private HomepageCardService cards;
    private HomepageSettingsService service;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
        auditor = mock(PanelActionAuditor.class);
        onlinePlayers = mock(OnlinePlayerCount.class);
        cards = mock(HomepageCardService.class);
        service = new HomepageSettingsService(kit.documents(), kit.unitOfWork(), auditor, onlinePlayers, cards);
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void aFreshInstallationShowsNothingExtra() {
        assertThat(service.current()).isEqualTo(HomepageLayout.DEFAULT);
        assertThat(service.published().onlinePlayers()).isEmpty();
        verifyNoInteractions(onlinePlayers);
    }

    @Test
    void savedSettingsAreTrimmedSurviveARestartAndAreAuditedByField() {
        HomepageLayout requested = new HomepageLayout("  Survival since 2012 ", " play.example.net ", true,
            List.of(new FooterLink(" Discord ", " https://discord.gg/example ", FooterLinkKind.DISCORD)));

        VersionedSettings<HomepageLayout> saved = change(0, requested);

        assertThat(saved.data()).isEqualTo(new HomepageLayout("Survival since 2012", "play.example.net", true, List.of(DISCORD)));
        HomepageSettingsService restarted = new HomepageSettingsService(kit.documents(), kit.unitOfWork(), auditor, onlinePlayers, cards);
        assertThat(restarted.current()).isEqualTo(saved.data());
        verify(auditor).recordStaffAction(any(), eq(OWNER),
            eq("Changed the homepage: tagline, server address, player count, footer links"));
    }

    @Test
    void savingTheSameSettingsAgainIsNotAudited() {
        change(0, HomepageLayout.DEFAULT);

        verify(auditor, never()).recordStaffAction(any(), any(), any());
    }

    @Test
    void thePlayerCountIsPublishedOnlyWhileTheSwitchIsOn() {
        when(onlinePlayers.total()).thenReturn(42);
        change(0, new HomepageLayout("", "", true, List.of()));

        assertThat(service.published().onlinePlayers()).isEqualTo(OptionalInt.of(42));
    }

    @Test
    void aStaleVersionIsRefused() {
        change(0, new HomepageLayout("First", "", false, List.of()));

        assertThatThrownBy(() -> change(0, new HomepageLayout("Second", "", false, List.of())))
            .isInstanceOf(StaleClientVersionException.class);
        assertThat(service.current().tagline()).isEqualTo("First");
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "/appeal", "//evil.example", "ftp://example.com", "https://user@example.com",
        "https://", "https://exa mple.com", "data:text/html,hi"})
    void footerLinksMustBeHttpOrHttpsAddresses(String url) {
        HomepageLayout requested = new HomepageLayout("", "", false, List.of(new FooterLink("Link", url, FooterLinkKind.OTHER)));

        assertThatThrownBy(() -> change(0, requested))
            .isInstanceOfSatisfying(BaseApplicationException.class,
                error -> assertThat(error.getMachineCode()).isEqualTo(HomepageSettingsValues.LINK_URL_INVALID_CODE));
    }

    @Test
    void theFooterHoldsAtMostEightLinks() {
        HomepageLayout requested = new HomepageLayout("", "", false, Collections.nCopies(9, DISCORD));

        assertThatThrownBy(() -> change(0, requested))
            .isInstanceOfSatisfying(BaseApplicationException.class,
                error -> assertThat(error.getMachineCode()).isEqualTo(HomepageSettingsValues.TOO_MANY_LINKS_CODE));
        assertThat(change(0, new HomepageLayout("", "", false, Collections.nCopies(8, DISCORD)))
            .data().footerLinks()).hasSize(8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"line\nbreak", "tab\taddress", "play example net"})
    void theServerAddressIsOneWordOnOneLine(String address) {
        HomepageLayout requested = new HomepageLayout("", address, false, List.of());

        assertThatThrownBy(() -> change(0, requested))
            .isInstanceOfSatisfying(BaseApplicationException.class,
                error -> assertThat(error.getMachineCode()).isEqualTo(HomepageSettingsValues.TEXT_INVALID_CODE));
    }

    @Test
    void textOverItsLimitOrAnEmptyLinkLabelIsRefused() {
        HomepageLayout longTagline = new HomepageLayout("x".repeat(HomepageSettingsValues.MAX_TAGLINE_LENGTH + 1), "", false,
            List.of());
        HomepageLayout emptyLabel = new HomepageLayout("", "", false,
            List.of(new FooterLink("  ", "https://example.com", FooterLinkKind.WEBSITE)));

        assertThatThrownBy(() -> change(0, longTagline))
            .isInstanceOf(BaseApplicationException.class);
        assertThatThrownBy(() -> change(0, emptyLabel))
            .isInstanceOf(BaseApplicationException.class);
        assertThat(service.current()).isEqualTo(HomepageLayout.DEFAULT);
    }

    @Test
    void aCardOnlyChangeStillMovesTheVersionSoAStaleEditorIsRefused() {
        when(cards.replaceWith(anyList())).thenReturn(true);
        List<HomepageCardEdit> edited = List.of(new HomepageCardEdit(null, "Rules", "", "", HomepageCardAction.LINK, "/rules", "", true));

        VersionedSettings<HomepageLayout> first = service.change(kit.server(), OWNER, 0, HomepageLayout.DEFAULT, edited);

        assertThat(first.version()).isEqualTo(1);
        assertThat(first.data().cardsRevision()).isEqualTo(1);
        verify(auditor).recordStaffAction(any(), eq(OWNER), eq("Changed the homepage: cards"));
        assertThatThrownBy(() -> service.change(kit.server(), OWNER, 0, HomepageLayout.DEFAULT, edited))
            .isInstanceOf(StaleClientVersionException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "javascript:alert(1)", "//evil.example"})
    void aLinkCardNeedsASafeAddress(String url) {
        List<HomepageCardEdit> edited = List.of(new HomepageCardEdit(null, "Rules", "", "", HomepageCardAction.LINK, url, "", true));

        assertThatThrownBy(() -> service.change(kit.server(), OWNER, 0, HomepageLayout.DEFAULT, edited))
            .isInstanceOfSatisfying(BaseApplicationException.class,
                error -> assertThat(error.getMachineCode()).isEqualTo(HomepageCardValues.CARD_INVALID_CODE));
        verifyNoInteractions(cards);
    }

    @Test
    void aCategoryCardNeedsACategoryAndDropsAnyAddress() {
        List<HomepageCardEdit> missing = List.of(new HomepageCardEdit(null, "Rules", "", "", HomepageCardAction.CATEGORY, "/x", " ", true));
        List<HomepageCardEdit> valid = List.of(new HomepageCardEdit(null, " Rules ", "", "", HomepageCardAction.CATEGORY, "/x", "c1", true));

        assertThatThrownBy(() -> service.change(kit.server(), OWNER, 0, HomepageLayout.DEFAULT, missing))
            .isInstanceOf(BaseApplicationException.class);
        service.change(kit.server(), OWNER, 0, HomepageLayout.DEFAULT, valid);

        verify(cards, atLeastOnce()).replaceWith(List.of(new HomepageCardEdit(null, "Rules", "", "", HomepageCardAction.CATEGORY, "", "c1", true)));
    }

    private VersionedSettings<HomepageLayout> change(long expectedVersion, HomepageLayout layout) {
        return service.change(kit.server(), OWNER, expectedVersion, layout, List.of());
    }
}
