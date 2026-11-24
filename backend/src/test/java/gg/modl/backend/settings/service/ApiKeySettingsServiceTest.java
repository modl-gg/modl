package gg.modl.backend.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.serverkey.service.ServerKeySecrets;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.instance.InstanceTestKit;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiKeySettingsServiceTest {
    private static final String CONFIGURED_KEY = "modl_configured_key_with_32_chars_x";

    @TempDir
    Path dataDirectory;

    private InstanceTestKit kit;
    private IdGenerator idGenerator;
    private OperatorNotice operatorNotice;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
        idGenerator = mock(IdGenerator.class);
        operatorNotice = mock(OperatorNotice.class);
        when(idGenerator.generateToken()).thenReturn("generated", "fresh");
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void generatesAKeyOnFirstBootAndAnnouncesItWithoutPrintingIt() {
        service(null).ensureInstanceKey(kit.server());

        assertThat(storedKey()).isEqualTo("modl_generated");
        verify(operatorNotice).publish("API key generated. View it in Panel > Settings > General > Minecraft servers.");
    }

    @Test
    void anExistingServerKeyIsKeptAndNothingIsAnnounced() {
        kit.instanceService().changeApiKey("modl_existing");

        service(null).ensureInstanceKey(kit.server());

        assertThat(storedKey()).isEqualTo("modl_existing");
        verify(operatorNotice, never()).publish(anyString());
    }

    @Test
    void theConfiguredKeyWinsAndIsManaged() {
        ApiKeySettingsService service = service(CONFIGURED_KEY);

        service.ensureInstanceKey(kit.server());

        assertThat(service.isManagedByConfig()).isTrue();
        assertThat(storedKey()).isEqualTo(CONFIGURED_KEY);
    }

    @Test
    void managedKeyCannotBeRegenerated() {
        assertThatThrownBy(() -> service(CONFIGURED_KEY).generateApiKey(kit.server())).isInstanceOf(ConflictException.class);
        assertThat(storedKey()).isNull();
    }

    @Test
    void regenerateReplacesTheServerKeyOnly() {
        kit.instanceService().changeApiKey("modl_old");

        assertThat(service(null).generateApiKey(kit.server())).isEqualTo("modl_generated");

        assertThat(storedKey()).isEqualTo("modl_generated");
        assertThat(kit.settingsRepository().find("apiKeys")).isEmpty();
    }

    @Test
    void anInstanceKeyNeverLooksLikeAServerKey() {
        when(idGenerator.generateToken()).thenReturn("msk_" + "a".repeat(43));

        String generated = service(null).generateApiKey(kit.server());

        assertThat(generated).doesNotStartWith(ServerKeySecrets.PREFIX);
        assertThat(ServerKeySecrets.looksLikeServerKey(generated)).isFalse();
    }

    @Test
    void revealAndExistenceReadTheServerKey() {
        ApiKeySettingsService service = service(null);
        assertThat(service.revealApiKey(kit.server())).isNull();
        assertThat(service.hasApiKey(kit.server())).isFalse();

        kit.instanceService().changeApiKey("modl_live");

        assertThat(service.revealApiKey(kit.server())).isEqualTo("modl_live");
        assertThat(service.hasApiKey(kit.server())).isTrue();
    }

    private String storedKey() {
        return kit.instanceRepository().find().orElseThrow().getApiKey();
    }

    private ApiKeySettingsService service(String configuredKey) {
        InstanceProperties properties = new InstanceProperties(null, null, null, configuredKey, null, null);
        return new ApiKeySettingsService(idGenerator, properties, kit.instanceService(properties), operatorNotice);
    }
}
