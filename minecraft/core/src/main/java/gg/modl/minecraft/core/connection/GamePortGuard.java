package gg.modl.minecraft.core.connection;

import java.util.Set;

public final class GamePortGuard {
    private GamePortGuard() {
    }

    public static void requireOwnPanelPort(ConnectionConfig config, Set<Integer> gamePorts, Set<Integer> rconPorts) {
        if (config.getMode() != ConnectionMode.EMBEDDED) return;
        int panelPort = config.getEmbedded().getPort();
        if (gamePorts.contains(panelPort)) throw takesTheGamePort(panelPort);
        if (rconPorts.contains(panelPort)) throw takesTheRconPort(panelPort);
    }

    private static ConnectionConfigException takesTheRconPort(int panelPort) {
        return new ConnectionConfigException(ConnectionConfig.FILE_NAME + ": " + ConnectionYaml.EMBEDDED_PORT + " is " + panelPort
                + ", the RCON port of this server (" + RconPorts.PORT + " in " + RconPorts.SERVER_PROPERTIES
                + "), so the built-in panel and RCON cannot both listen on it. Set " + ConnectionYaml.EMBEDDED_PORT
                + " to another port your host gives this server, or change " + RconPorts.PORT + ".");
    }

    private static ConnectionConfigException takesTheGamePort(int panelPort) {
        return new ConnectionConfigException(ConnectionConfig.FILE_NAME + ": " + ConnectionYaml.EMBEDDED_PORT + " is " + panelPort
                + ", the port players join on, so the built-in panel would take it and nobody could join. Set "
                + ConnectionYaml.EMBEDDED_PORT + " to a second port your host gives this server (on game hosts: add an"
                + " allocation in the server's Network tab), or reach the panel through a tunnel (see "
                + WikiPages.PANEL_PORT_AND_TUNNELS + ").");
    }
}
