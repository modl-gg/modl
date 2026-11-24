package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.connection.ConnectionYaml;
import gg.modl.minecraft.core.connection.EmbeddedSettings;
import gg.modl.minecraft.core.connection.WikiPages;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.StringUtil;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;

final class PanelPortNotice {
    static final String GAME_HOST_PORT_VARIABLE = "SERVER_PORT";
    static final String GAME_HOST_MARKER_VARIABLE = "P_SERVER_UUID";

    private final EmbeddedSettings settings;
    private final String configPath;
    private final Function<String, String> environment;

    PanelPortNotice(EmbeddedSettings settings, String configPath, Function<String, String> environment) {
        this.settings = settings;
        this.configPath = configPath;
        this.environment = environment;
    }

    void announce(PluginLogger logger) {
        logger.info(listening());
        String hint = gameHostHint();
        if (hint != null) logger.warning(hint);
    }

    String listening() {
        int port = settings.getPort();
        if (settings.bind().isLoopback()) {
            return "The built-in panel is listening on port " + port + " of this machine only (" + ConnectionYaml.EMBEDDED_BIND_ADDRESS
                    + " " + settings.getBindAddress() + "). Open it through your tunnel or reverse proxy"
                    + (settings.hasPublicUrl() ? " at " + settings.getPublicUrl() : "") + ".";
        }
        String where = settings.hasPublicUrl() ? " for " + settings.getPublicUrl() : "";
        return "The built-in panel is listening on port " + port + where + ". It opens only if your host forwards port " + port
                + " to this server; if it does not load, set " + ConnectionYaml.EMBEDDED_PORT + " in " + configPath
                + " to a port your host gave this server, or use a tunnel (see " + WikiPages.PANEL_PORT_AND_TUNNELS + ").";
    }

    @Nullable String gameHostHint() {
        if (settings.bind().isLoopback()) return null;
        String gamePort = environment.apply(GAME_HOST_PORT_VARIABLE);
        boolean gameHost = !StringUtil.isBlank(environment.apply(GAME_HOST_MARKER_VARIABLE)) || !StringUtil.isBlank(gamePort);
        if (!gameHost) return null;
        String allocated = StringUtil.isBlank(gamePort) ? "" : " (its game port is " + gamePort.trim() + ")";
        return "This server runs on a game host" + allocated + ", which only forwards the ports it allocates to the server."
                + " Add an allocation for port " + settings.getPort() + " in the host panel's Network tab, or set "
                + ConnectionYaml.EMBEDDED_PORT + " in " + configPath + " to an extra port it already allocated.";
    }
}
