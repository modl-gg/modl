package gg.modl.backend.instance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.jooq.Tables;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.instance.setup.SetupCodeService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.service.ServerProvisioningService;
import gg.modl.backend.settings.service.ApiKeySettingsService;
import gg.modl.backend.staff.service.StaffService;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

class InstanceBootstrapperTest {
    private static final String OWNER = "owner@example.com";

    @TempDir
    Path dataDirectory;

    private InstanceTestKit kit;
    private InstanceSecretsService secretsService;
    private ServerProvisioningService provisioningService;
    private ApiKeySettingsService apiKeySettingsService;
    private SetupCodeService setupCodeService;
    private StaffService staffService;
    private OperatorNotice operatorNotice;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
        kit.write(() -> kit.instanceRepository().claim(OWNER, null, kit.clock().instant()));
        kit.instanceService().changeServerName("Renamed By Owner");
        secretsService = mock(InstanceSecretsService.class);
        provisioningService = mock(ServerProvisioningService.class);
        apiKeySettingsService = mock(ApiKeySettingsService.class);
        setupCodeService = mock(SetupCodeService.class);
        staffService = mock(StaffService.class);
        operatorNotice = mock(OperatorNotice.class);
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void theSecretsExistBeforeTheInstanceIsProvisioned() {
        start(InstanceTestKit.unconfigured());

        InOrder order = inOrder(secretsService, provisioningService, apiKeySettingsService);
        order.verify(secretsService).ensureInitialized();
        order.verify(provisioningService).provision(any());
        order.verify(apiKeySettingsService).ensureInstanceKey(any());
    }

    @Test
    void anOwnerChosenServerNameSurvivesARestartWithoutConfiguredName() {
        start(InstanceTestKit.unconfigured());

        assertThat(stored().getServerName()).isEqualTo("Renamed By Owner");
    }

    @Test
    void anExplicitlyConfiguredServerNameIsApplied() {
        start(new InstanceProperties(null, null, "Configured Name", null, null, null));

        assertThat(stored().getServerName()).isEqualTo("Configured Name");
        assertThat(kit.server().getServerName()).isEqualTo("Configured Name");
    }

    @Test
    void aConfiguredApiKeyShorterThan32CharactersFailsStartupBeforeAnyDatabaseWork() {
        assertThatThrownBy(() -> start(withApiKey("too-short-key")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MODL_API_KEY must be at least 32 characters");
        verifyNoInteractions(secretsService, provisioningService);
    }

    @Test
    void aConfiguredApiKeyThatDoesNotFitItsColumnFailsStartupWithAReason() {
        String tooLong = "s3cret".repeat(43);

        assertThatThrownBy(() -> start(withApiKey(tooLong)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("MODL_API_KEY must be at most 255 characters long; leave it empty to let modl generate one")
            .satisfies(failure -> assertThat(failure.toString()).doesNotContain("s3cret"));
        verifyNoInteractions(secretsService, provisioningService);
    }

    @Test
    void theLongestAllowedApiKeyIsExactlyTheColumnWidth() {
        assertThat(InstanceProperties.MAX_API_KEY_LENGTH).isEqualTo(TextLimits.limit(Tables.INSTANCE.API_KEY));

        start(withApiKey("k".repeat(InstanceProperties.MAX_API_KEY_LENGTH)));

        verify(apiKeySettingsService).ensureInstanceKey(any());
    }

    @Test
    void thePropertiesNeverRenderEitherKey() {
        InstanceProperties properties = new InstanceProperties(null, null, null, "configured-secret", "internal-secret", null);

        assertThat(properties.toString()).doesNotContain("configured-secret", "internal-secret");
    }

    @Test
    void aClaimedInstanceWithoutAKnownPanelUrlWarnsOnce() {
        start(InstanceTestKit.unconfigured());

        verify(operatorNotice).publish(anyString(), eq("http://localhost:8080"));
        verify(setupCodeService).revoke();
    }

    @Test
    void anInstanceThatNeedsACodeAnnouncesItInsteadOfTheWarning() {
        when(setupCodeService.issueIfNeeded()).thenReturn(true);

        start(InstanceTestKit.unconfigured());

        verify(setupCodeService, never()).revoke();
        verify(operatorNotice, never()).publish(anyString(), any());
    }

    @Test
    void aChangedConfiguredOwnerReplacesThePreviousOwnerAndOffboardsThem() {
        start(withOwner(" New@Example.com "));

        assertThat(stored().getAdminEmail()).isEqualTo("new@example.com");
        verify(staffService, atLeastOnce()).offboardPreviousAdminEmail(
            argThat(server -> "new@example.com".equals(server.getAdminEmail())), eq(OWNER));
    }

    @Test
    void aFailedOffboardingKeepsThePreviousOwnerSoTheNextStartRepeatsIt() {
        doThrow(new IllegalStateException("sessions unavailable")).when(staffService).offboardPreviousAdminEmail(any(), any());

        assertThatThrownBy(() -> start(withOwner("new@example.com"))).isInstanceOf(IllegalStateException.class);

        assertThat(stored().getAdminEmail()).isEqualTo(OWNER);
    }

    @Test
    void anUnchangedConfiguredOwnerIsLeftAlone() {
        start(withOwner(OWNER));

        verify(staffService, never()).offboardPreviousAdminEmail(any(), any());
    }

    private Server stored() {
        return kit.instanceRepository().find().orElseThrow();
    }

    private static InstanceProperties withApiKey(String apiKey) {
        return new InstanceProperties(null, null, null, apiKey, null, null);
    }

    private static InstanceProperties withOwner(String email) {
        return new InstanceProperties(null, email, null, null, null, null);
    }

    private void start(InstanceProperties properties) {
        new InstanceBootstrapper(properties, kit.unitOfWork(), kit.instanceRepository(), kit.instanceService(properties),
            kit.instanceContext(), secretsService, provisioningService, apiKeySettingsService, setupCodeService, staffService,
            kit.publicUrls(properties), operatorNotice, kit.clock()).afterSingletonsInstantiated();
    }
}
