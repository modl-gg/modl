package dev.simplix.cirrus.common.packet;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import java.util.HashMap;
import java.util.Map;

final class LegacyItemIds {

    private static final Map<Integer, ClientVersion> ADDED_AFTER_1_8 = new HashMap<>();

    static {
        addedIn(ClientVersion.V_1_9, 198, 212);
        addedIn(ClientVersion.V_1_9, 255, 255);
        addedIn(ClientVersion.V_1_9, 426, 426);
        addedIn(ClientVersion.V_1_9, 432, 448);
        addedIn(ClientVersion.V_1_10, 213, 217);
        addedIn(ClientVersion.V_1_11, 218, 234);
        addedIn(ClientVersion.V_1_11, 449, 450);
        addedIn(ClientVersion.V_1_11_1, 452, 452);
        addedIn(ClientVersion.V_1_12, 235, 252);
        addedIn(ClientVersion.V_1_12, 453, 453);
    }

    private LegacyItemIds() {
    }

    static boolean existsOn(int itemId, ClientVersion version) {
        if (itemId < 0) {
            return false;
        }
        if (version.isNewerThanOrEquals(ClientVersion.V_1_13)) {
            return true;
        }
        ClientVersion added = ADDED_AFTER_1_8.get(itemId);
        return added == null || version.isNewerThanOrEquals(added);
    }

    private static void addedIn(ClientVersion version, int firstId, int lastId) {
        for (int id = firstId; id <= lastId; id++) {
            ADDED_AFTER_1_8.put(id, version);
        }
    }
}
