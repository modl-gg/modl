package gg.modl.backend.theme.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.theme.data.CustomStylesheet;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CustomCssServiceTest {
    private static final String OWNER = "owner@example.com";
    private static final String CSS = "[data-modl=\"card\"] { border-radius: 0; }";

    @TempDir
    Path dataDirectory;

    private InstanceTestKit kit;
    private PanelActionAuditor auditor;
    private CustomCssService service;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
        auditor = mock(PanelActionAuditor.class);
        service = new CustomCssService(kit.documents(), kit.unitOfWork(), auditor);
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void nothingIsPublishedUntilStylesAreSaved() {
        assertThat(service.published().revision()).isEmpty();
    }

    @Test
    void savedStylesArePublishedUnderTheirVersionAndAudited() {
        service.change(kit.server(), OWNER, 0, CSS);

        assertThat(service.published().revision()).hasSize(16).isEqualTo(new CustomStylesheet(CSS).revision());
        assertThat(new CustomCssService(kit.documents(), kit.unitOfWork(), auditor).versioned().data().css()).isEqualTo(CSS);
        verify(auditor).recordStaffAction(any(), eq(OWNER), eq("Changed the custom CSS of the public site (" + CSS.length() + " bytes)"));
    }

    @Test
    void clearingTheStylesUnpublishesThemAndIsAudited() {
        long version = service.change(kit.server(), OWNER, 0, CSS).version();

        service.change(kit.server(), OWNER, version, "");

        assertThat(service.published().revision()).isEmpty();
        verify(auditor).recordStaffAction(any(), eq(OWNER), eq("Removed the custom CSS of the public site"));
    }

    @Test
    void savingTheSameStylesAgainKeepsTheVersionAndIsNotAuditedTwice() {
        long version = service.change(kit.server(), OWNER, 0, CSS).version();

        assertThat(service.change(kit.server(), OWNER, version, CSS).version()).isEqualTo(version);
        verify(auditor).recordStaffAction(any(), any(), any());
    }

    @Test
    void rejectedStylesKeepTheStoredOnes() {
        long version = service.change(kit.server(), OWNER, 0, CSS).version();

        assertThatThrownBy(() -> service.change(kit.server(), OWNER, version, "@import url(x.css);"))
            .isInstanceOf(ValidationException.class);
        assertThat(service.versioned().data().css()).isEqualTo(CSS);
        verify(auditor, never()).recordStaffAction(any(), eq(OWNER), eq("Removed the custom CSS of the public site"));
    }
}
