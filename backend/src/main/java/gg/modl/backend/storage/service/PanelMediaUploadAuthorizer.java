package gg.modl.backend.storage.service;

import gg.modl.backend.server.data.Server;
import org.jspecify.annotations.Nullable;

public interface PanelMediaUploadAuthorizer {
    void authorizePresign(Server server, @Nullable String staffEmail, String uploadType);

    void authorizeConfirm(Server server, @Nullable String staffEmail, String key);
}
