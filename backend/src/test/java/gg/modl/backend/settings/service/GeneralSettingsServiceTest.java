package gg.modl.backend.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.StaleClientVersionException;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.instance.InstanceService;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.instance.PublicUrlChangedEvent;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.GeneralSettings;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneralSettingsServiceTest {
    private static final String SETTINGS_TYPE = "general";
    private static final String PANEL = "https://panel.example.com";

    @TempDir
    Path dataDirectory;

    private InstanceTestKit kit;
    private GeneralSettingsService service;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
        service = service(null, null);
        kit.store(SETTINGS_TYPE, Map.of("panelIconUrl", "https://cdn.example/panel.png"));
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void responsesExposeTheEffectivePanelUrlAndWhetherConfigManagesIt() {
        GeneralSettings settings = service(PANEL, null).getGeneralSettings(kit.server());

        assertThat(settings.getPublicUrl()).isEqualTo(PANEL);
        assertThat(settings.isPublicUrlManagedByConfig()).isTrue();
    }

    @Test
    void anInvalidPanelUrlIsRejectedBeforeAnythingIsSaved() {
        GeneralSettings patch = GeneralSettings.builder().serverDisplayName("Renamed").publicUrl(PANEL + "/path").build();

        assertThatThrownBy(() -> patch(service, patch)).isInstanceOf(ValidationException.class);
        assertNothingSaved();
    }

    @Test
    void aConfigManagedPanelUrlCannotBeChangedFromThePanel() {
        GeneralSettings patch = GeneralSettings.builder().publicUrl("https://other.example.com").build();

        assertThatThrownBy(() -> patch(service(PANEL, null), patch)).isInstanceOfSatisfying(ConflictException.class,
            refusal -> assertThat(refusal.getMachineCode()).isEqualTo(GeneralSettingsService.PUBLIC_URL_MANAGED_CODE));
        assertNothingSaved();
    }

    @Test
    void resubmittingTheConfigManagedPanelUrlSavesTheOtherFields() {
        GeneralSettings patch = GeneralSettings.builder().serverDisplayName("Renamed").publicUrl("HTTPS://panel.example.com/").build();

        patch(service(PANEL, null), patch);

        assertThat(storedInstance().getServerName()).isEqualTo("Renamed");
        assertThat(storedInstance().getPublicUrl()).isNull();
    }

    @Test
    void aNewPanelUrlIsStoredAsANormalizedOriginAndAnnouncedOnce() {
        GeneralSettings patch = GeneralSettings.builder().publicUrl("HTTPS://New.Example.com:443/").build();

        VersionedSettings<GeneralSettings> saved = patch(service, patch);

        assertThat(storedInstance().getPublicUrl()).isEqualTo("https://new.example.com");
        assertThat(saved.data().getPublicUrl()).isEqualTo("https://new.example.com");
        verify(kit.events(), times(1)).publishEvent(new PublicUrlChangedEvent("https://new.example.com"));
    }

    @Test
    void theNameLivesOnlyOnTheInstanceRow() {
        kit.store(SETTINGS_TYPE, Map.of("serverDisplayName", "Stale", "panelIconUrl", "https://cdn.example/panel.png"));

        GeneralSettings settings = service.getGeneralSettings(kit.server());

        assertThat(settings.getServerDisplayName()).isEqualTo(InstanceTestKit.SERVER_NAME);
        assertThat(settings.getPanelIconUrl()).isEqualTo("https://cdn.example/panel.png");
    }

    @Test
    void aNewNameRenamesTheInstanceWithoutStoringItInTheSettingsRow() {
        VersionedSettings<GeneralSettings> saved =
            patch(service, GeneralSettings.builder().serverDisplayName("  Block Party  ").defaultLanguage("de").build());

        assertThat(saved.data().getServerDisplayName()).isEqualTo("Block Party");
        assertThat(kit.server().getServerName()).isEqualTo("Block Party");
        assertThat(kit.stored(SETTINGS_TYPE)).containsEntry("defaultLanguage", "de").doesNotContainKey("serverDisplayName");
    }

    @Test
    void aStaleVersionChangesNeitherTheSettingsNorTheIdentity() {
        GeneralSettings patch = GeneralSettings.builder().serverDisplayName("Renamed").publicUrl(PANEL).build();

        assertThatThrownBy(() -> service.patchGeneralSettings(kit.server(), 0, patch))
            .isInstanceOf(StaleClientVersionException.class);
        assertNothingSaved();
    }

    @Test
    void aFailedIdentityWriteRollsBackTheSettingsChange() {
        InstanceService failing = mock(InstanceService.class);
        when(failing.changePublicUrl(PANEL)).thenThrow(new IllegalStateException("instance row unavailable"));
        GeneralSettingsService failingService = new GeneralSettingsService(kit.documents(), kit.unitOfWork(),
            kit.publicUrls(InstanceTestKit.unconfigured()), failing, InstanceTestKit.unconfigured());
        GeneralSettings patch = GeneralSettings.builder().defaultLanguage("de").publicUrl(PANEL).build();

        assertThatThrownBy(() -> patch(failingService, patch)).isInstanceOf(IllegalStateException.class);
        assertNothingSaved();
    }

    @Test
    void responsesExposeWhetherConfigManagesTheServerName() {
        assertThat(service.getGeneralSettings(kit.server()).isServerNameManaged()).isFalse();
        assertThat(service(null, "Server").getGeneralSettings(kit.server()).isServerNameManaged()).isTrue();
    }

    @Test
    void aConfigManagedServerNameCannotBeChangedFromThePanel() {
        GeneralSettings patch = GeneralSettings.builder().serverDisplayName("Block Party").build();

        assertThatThrownBy(() -> patch(service(null, "Server"), patch)).isInstanceOfSatisfying(ConflictException.class,
            refusal -> assertThat(refusal.getMachineCode()).isEqualTo(GeneralSettingsService.SERVER_NAME_MANAGED_CODE));
        assertNothingSaved();
    }

    @Test
    void resubmittingTheConfigManagedServerNameSavesTheOtherFields() {
        patch(service(null, "Server"), GeneralSettings.builder().serverDisplayName(" Server ").defaultLanguage("de").build());

        assertThat(kit.stored(SETTINGS_TYPE)).containsEntry("defaultLanguage", "de");
    }

    @Test
    void aBlankNameIsRejectedBeforeAnythingIsSaved() {
        assertThatThrownBy(() -> patch(service, GeneralSettings.builder().serverDisplayName(" ").build()))
            .isInstanceOf(ValidationException.class);
        assertNothingSaved();
    }

    @Test
    void anUnchangedNameKeepsTheVersion() {
        VersionedSettings<GeneralSettings> saved = patch(service, GeneralSettings.builder().serverDisplayName("Server").build());

        assertThat(saved.version()).isEqualTo(1);
    }

    private void assertNothingSaved() {
        assertThat(kit.version(SETTINGS_TYPE)).isEqualTo(1);
        assertThat(kit.stored(SETTINGS_TYPE)).doesNotContainKey("defaultLanguage");
        assertThat(storedInstance().getServerName()).isEqualTo(InstanceTestKit.SERVER_NAME);
        assertThat(storedInstance().getPublicUrl()).isNull();
        verify(kit.events(), never()).publishEvent(any(PublicUrlChangedEvent.class));
    }

    private Server storedInstance() {
        return kit.instanceRepository().find().orElseThrow();
    }

    private VersionedSettings<GeneralSettings> patch(GeneralSettingsService target, GeneralSettings patch) {
        return target.patchGeneralSettings(kit.server(), 1, patch);
    }

    private GeneralSettingsService service(String configuredUrl, String configuredName) {
        InstanceProperties properties = new InstanceProperties(configuredUrl, null, configuredName, null, null, null);
        return new GeneralSettingsService(
            kit.documents(), kit.unitOfWork(), kit.publicUrls(properties), kit.instanceService(properties), properties);
    }
}
