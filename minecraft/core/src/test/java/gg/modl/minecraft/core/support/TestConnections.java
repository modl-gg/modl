package gg.modl.minecraft.core.support;

import gg.modl.minecraft.core.boot.PlatformType;
import gg.modl.minecraft.core.boot.VersionMismatchReports;
import gg.modl.minecraft.core.connection.BackendClientIdentity;
import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.connection.BackendEndpoint;

public final class TestConnections {
    private TestConnections() {
    }

    public static BackendConnections to(String baseUrl, String apiKey) {
        return to(BackendEndpoint.of(baseUrl, apiKey));
    }

    public static BackendConnections to(BackendEndpoint endpoint) {
        return reportingTo(endpoint, new VersionMismatchReports());
    }

    public static BackendConnections reportingTo(BackendEndpoint endpoint, VersionMismatchReports mismatches) {
        return new BackendConnections(endpoint, BackendClientIdentity.newBoot(PlatformType.SPIGOT), mismatches);
    }
}
