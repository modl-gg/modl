package gg.modl.backend.databasemove.service;

import gg.modl.address.AddressScope;
import gg.modl.backend.database.selection.DatabaseConnection;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;

final class ConnectionPrivacy {
    private ConnectionPrivacy() {
    }

    static Optional<TargetWarning> warning(DatabaseConnection connection) {
        if (connection.isBuiltIn() || connection.tls() || !reachesPublicAddress(connection.host())) {
            return Optional.empty();
        }
        return Optional.of(new TargetWarning.NotEncrypted(connection.host()));
    }

    private static boolean reachesPublicAddress(String host) {
        try {
            return Arrays.stream(InetAddress.getAllByName(host)).map(AddressScope::classify).anyMatch(AddressScope::isPublic);
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
