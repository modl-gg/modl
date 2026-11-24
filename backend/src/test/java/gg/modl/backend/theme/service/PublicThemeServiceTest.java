package gg.modl.backend.theme.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import gg.modl.backend.database.StaleClientVersionException;
import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.settings.service.VersionedSettings;
import gg.modl.backend.storage.StorageOrigins;
import gg.modl.backend.theme.data.PublicSiteTheme;
import gg.modl.backend.theme.data.ThemeColorScheme;
import gg.modl.backend.theme.data.ThemeFont;
import gg.modl.backend.theme.data.ThemeRadius;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicThemeServiceTest {
    private static final String OWNER = "owner@example.com";
    private static final String UPLOADED = "/v1/storage/objects/modl_instance/theme_background/a.png";
    private static final StorageOrigins STORAGE = () -> Set.of("http://minio.local:9000");

    @TempDir
    Path dataDirectory;

    private InstanceTestKit kit;
    private PanelActionAuditor auditor;
    private PublicThemeService service;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
        auditor = mock(PanelActionAuditor.class);
        service = new PublicThemeService(kit.documents(), kit.unitOfWork(), auditor, STORAGE);
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void theDefaultThemeChangesNothing() {
        assertThat(service.current()).isEqualTo(PublicSiteTheme.DEFAULT);
        assertThat(service.versioned().version()).isZero();
    }

    @Test
    void aSavedThemeIsNormalizedSurvivesARestartAndIsAuditedByField() {
        PublicSiteTheme requested = new PublicSiteTheme(" #FF8800 ", "", "#0B1020", UPLOADED, ThemeFont.INTER,
            ThemeRadius.LARGE, ThemeColorScheme.DARK);

        VersionedSettings<PublicSiteTheme> saved = service.change(kit.server(), OWNER, 0, requested);

        assertThat(saved.data().primaryColor()).isEqualTo("#ff8800");
        assertThat(saved.data().backgroundColor()).isEqualTo("#0b1020");
        PublicThemeService restarted = new PublicThemeService(kit.documents(), kit.unitOfWork(), auditor, STORAGE);
        assertThat(restarted.current()).isEqualTo(saved.data());
        verify(auditor).recordStaffAction(any(), eq(OWNER), eq(
            "Changed the public site theme: primary color, background color, background image, font, corner radius, default color scheme"));
    }

    @Test
    void savingTheSameThemeAgainIsNotAudited() {
        service.change(kit.server(), OWNER, 0, PublicSiteTheme.DEFAULT);

        verify(auditor, never()).recordStaffAction(any(), any(), any());
    }

    @Test
    void aStaleVersionIsRefused() {
        service.change(kit.server(), OWNER, 0, withPrimary("#112233"));

        assertThatThrownBy(() -> service.change(kit.server(), OWNER, 0, withPrimary("#445566")))
            .isInstanceOf(StaleClientVersionException.class);
        assertThat(service.current().primaryColor()).isEqualTo("#112233");
    }

    @ParameterizedTest
    @ValueSource(strings = {"red", "#fff", "#12345g", "#1234567", "rgb(1,2,3)"})
    void colorsThatAreNotSixDigitHexAreRefused(String color) {
        assertThatThrownBy(() -> service.change(kit.server(), OWNER, 0, withPrimary(color)))
            .isInstanceOfSatisfying(BaseApplicationException.class,
                error -> assertThat(error.getMachineCode()).isEqualTo(PublicThemeValues.COLOR_INVALID_CODE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "//evil.example/a.png", "/a.png\") , url(\"x", "https://user@example.com/a.png",
        "data:image/png;base64,AAAA", "/a b.png", "ftp://example.com/a.png", "http://cdn.example.com/bg.png"})
    void unsafeBackgroundImagesAreRefused(String url) {
        assertThatThrownBy(() -> service.change(kit.server(), OWNER, 0, withBackgroundImage(url)))
            .isInstanceOfSatisfying(BaseApplicationException.class,
                error -> assertThat(error.getMachineCode()).isEqualTo(PublicThemeValues.BACKGROUND_IMAGE_INVALID_CODE));
    }

    @ParameterizedTest
    @ValueSource(strings = {UPLOADED, "https://cdn.example.com/bg.webp", "http://minio.local:9000/bucket/bg.png"})
    void uploadedAndHttpsBackgroundImagesAreAccepted(String url) {
        assertThat(service.change(kit.server(), OWNER, 0, withBackgroundImage(url)).data().backgroundImageUrl()).isEqualTo(url);
    }

    private static PublicSiteTheme withPrimary(String color) {
        return new PublicSiteTheme(color, "", "", "", ThemeFont.SYSTEM, ThemeRadius.MEDIUM, ThemeColorScheme.SYSTEM);
    }

    private static PublicSiteTheme withBackgroundImage(String url) {
        return new PublicSiteTheme("", "", "", url, ThemeFont.SYSTEM, ThemeRadius.MEDIUM, ThemeColorScheme.SYSTEM);
    }
}
