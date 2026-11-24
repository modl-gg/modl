package gg.modl.backend.databasemove.service;

import gg.modl.backend.database.selection.DatabaseLocation;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

final class SameDatabase {
    private SameDatabase() {
    }

    static boolean at(DatabaseLocation target, DatabaseLocation current) {
        if (target.equals(current)) {
            return true;
        }
        if (target.isBuiltIn() || current.isBuiltIn() || target.dialect() != current.dialect() || target.port() != current.port()
            || !target.database().equals(current.database())) {
            return false;
        }
        Set<InetAddress> currentAddresses = addresses(current.host());
        return addresses(target.host()).stream().anyMatch(currentAddresses::contains);
    }

    private static Set<InetAddress> addresses(String host) {
        try {
            return Arrays.stream(InetAddress.getAllByName(host)).collect(Collectors.toUnmodifiableSet());
        } catch (UnknownHostException e) {
            return Set.of();
        }
    }
}
