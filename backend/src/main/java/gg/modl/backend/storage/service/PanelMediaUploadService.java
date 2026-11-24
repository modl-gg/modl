package gg.modl.backend.storage.service;

import gg.modl.backend.server.data.Server;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PanelMediaUploadService {
    private final PanelMediaUploadAuthorizer uploadAuthorizer;
    private final UploadOrchestrationService uploadOrchestrationService;

    public UploadOrchestrationService.PresignOutcome presign(
        Server server,
        @Nullable String staffEmail,
        UploadOrchestrationService.UploadPresignRequest request
    ) {
        uploadAuthorizer.authorizePresign(server, staffEmail, request.uploadType());
        return uploadOrchestrationService.presign(server, request);
    }

    public UploadOrchestrationService.ConfirmOutcome confirm(Server server, @Nullable String staffEmail, String key) {
        uploadAuthorizer.authorizeConfirm(server, staffEmail, key);
        return uploadOrchestrationService.confirm(server, key);
    }
}
