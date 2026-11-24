package gg.modl.backend.storage.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import org.junit.jupiter.api.Test;

class PanelMediaUploadServiceTest {

    private static final Server SERVER = TestServers.server("server", "admin@example.com");
    private static final String STAFF_EMAIL = "staff@example.com";
    private static final String KEY = "db/tickets/t1/upload.png";
    private static final UploadOrchestrationService.UploadPresignRequest PRESIGN_REQUEST =
        new UploadOrchestrationService.UploadPresignRequest("ticket", "upload.png", "image/png", 1024, "t1", null);

    private final PanelMediaUploadAuthorizer uploadAuthorizer = mock(PanelMediaUploadAuthorizer.class);
    private final UploadOrchestrationService uploadOrchestrationService = mock(UploadOrchestrationService.class);
    private final PanelMediaUploadService service = new PanelMediaUploadService(uploadAuthorizer, uploadOrchestrationService);

    @Test
    void presignIsAuthorizedForTheRequestedUploadTypeBeforeStorageIsReserved() {
        UploadOrchestrationService.PresignOutcome outcome =
            new UploadOrchestrationService.PresignOutcome(UploadOrchestrationService.PresignStatus.SUCCESS, null, null);
        when(uploadOrchestrationService.presign(SERVER, PRESIGN_REQUEST)).thenReturn(outcome);

        assertSame(outcome, service.presign(SERVER, STAFF_EMAIL, PRESIGN_REQUEST));

        verify(uploadAuthorizer).authorizePresign(SERVER, STAFF_EMAIL, "ticket");
    }

    @Test
    void deniedPresignNeverReachesStorage() {
        doThrow(new ForbiddenException("denied")).when(uploadAuthorizer).authorizePresign(SERVER, STAFF_EMAIL, "ticket");

        assertThrows(ForbiddenException.class, () -> service.presign(SERVER, STAFF_EMAIL, PRESIGN_REQUEST));

        verifyNoInteractions(uploadOrchestrationService);
    }

    @Test
    void confirmIsAuthorizedForTheStoredKey() {
        UploadOrchestrationService.ConfirmOutcome outcome =
            UploadOrchestrationService.ConfirmOutcome.status(UploadOrchestrationService.ConfirmStatus.UPLOAD_NOT_FOUND);
        when(uploadOrchestrationService.confirm(SERVER, KEY)).thenReturn(outcome);

        assertSame(outcome, service.confirm(SERVER, STAFF_EMAIL, KEY));

        verify(uploadAuthorizer).authorizeConfirm(SERVER, STAFF_EMAIL, KEY);
    }

    @Test
    void deniedConfirmNeverRecordsTheUpload() {
        doThrow(new ForbiddenException("denied")).when(uploadAuthorizer).authorizeConfirm(SERVER, STAFF_EMAIL, KEY);

        assertThrows(ForbiddenException.class, () -> service.confirm(SERVER, STAFF_EMAIL, KEY));

        verify(uploadOrchestrationService, never()).confirm(any(Server.class), anyString());
    }
}
