package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.api.http.response.StartupResponse;
import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.connection.PanelUrl;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class BackendSession {
    BackendConnections connections;
    StartupResponse startup;
    @Getter(AccessLevel.NONE) @Nullable String sourcePanelUrl;

    static BackendSession create(BackendConnections connections, StartupResponse startup, BackendSource source) {
        return new BackendSession(connections, startup, source.currentPanelUrl());
    }

    public void resolvePanelUrl(PanelUrl panelUrl) {
        panelUrl.resolve(startup.getPanelUrl());
        panelUrl.update(sourcePanelUrl);
    }

    public @Nullable String serverInstanceId() {
        String id = startup.getServerInstanceId();
        return id == null || id.trim().isEmpty() ? null : id.trim();
    }
}
