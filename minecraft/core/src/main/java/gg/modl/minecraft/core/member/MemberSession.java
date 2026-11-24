package gg.modl.minecraft.core.member;

import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.response.StartupResponse;
import gg.modl.minecraft.core.HttpManager;
import gg.modl.minecraft.core.connection.BackendConnections;
import lombok.AccessLevel;
import lombok.Getter;

@Getter
public final class MemberSession {
    private final BackendConnections connections;
    private final StartupResponse startup;
    @Getter(AccessLevel.NONE)
    private final ModlHttpClient httpClient;
    @Getter(AccessLevel.NONE)
    private volatile boolean closed;

    private MemberSession(BackendConnections connections, StartupResponse startup, ModlHttpClient httpClient) {
        this.connections = connections;
        this.startup = startup;
        this.httpClient = httpClient;
    }

    public static MemberSession open(BackendConnections connections, StartupResponse startup, boolean debugHttp) {
        return of(connections, startup, new HttpManager(connections, debugHttp).getHttpClient());
    }

    public static MemberSession of(BackendConnections connections, StartupResponse startup, ModlHttpClient httpClient) {
        return new MemberSession(connections, startup, httpClient);
    }

    public ModlHttpClient httpClient() {
        if (closed) throw new IllegalStateException("this server's session with the modl server has ended");
        return httpClient;
    }

    public boolean isClosed() {
        return closed;
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        httpClient.shutdown();
    }
}
