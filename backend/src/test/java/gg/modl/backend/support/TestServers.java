package gg.modl.backend.support;

import gg.modl.backend.server.data.Server;

public final class TestServers {

    private TestServers() {
    }

    public static Server server(String serverName, String adminEmail) {
        Server server = new Server(serverName);
        server.setAdminEmail(adminEmail);
        return server;
    }
}
