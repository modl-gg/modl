package gg.modl.backend.instance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import gg.modl.backend.infrastructure.web.HttpOrigin;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceServiceTest {
    private static final String PANEL = "https://panel.example.com";

    @TempDir
    Path dataDirectory;

    private InstanceTestKit kit;
    private InstanceService service;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
        service = kit.instanceService();
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void aRetriedClaimRefreshesTheContextAndAnnouncesTheAddressOnce() {
        assertThat(service.claim(" Owner@Example.com ", PANEL)).isTrue();

        assertThat(kit.server().getAdminEmail()).isEqualTo("owner@example.com");
        verify(kit.events(), times(1)).publishEvent(new PublicUrlChangedEvent(PANEL));
    }

    @Test
    void aRolledBackChangeNeverReachesTheContextOrListeners() {
        assertThatThrownBy(() -> kit.unitOfWork().write(() -> {
            service.changePublicUrl(PANEL);
            service.changeServerName("Uncommitted");
            throw new IllegalStateException("the outer unit fails");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(kit.instanceContext().refresh().getServerName()).isEqualTo(InstanceTestKit.SERVER_NAME);
        assertThat(kit.server().getPublicUrl()).isNull();
        verify(kit.events(), never()).publishEvent(any(PublicUrlChangedEvent.class));
    }

    @Test
    void anObservedAddressIsCapturedOnlyOnce() {
        assertThat(service.capturePublicUrlIfUnknown(new HttpOrigin("https", "panel.example.com", 443))).isTrue();
        assertThat(service.capturePublicUrlIfUnknown(new HttpOrigin("https", "other.example.com", 443))).isFalse();

        assertThat(kit.server().getPublicUrl()).isEqualTo(PANEL);
    }
}
