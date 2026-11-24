package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.service.sync.StatWipeExecutor;
import gg.modl.minecraft.core.service.sync.StatWipeOutcome;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.StatWipePushEvent;
import gg.modl.proto.modl.v1.SyncPendingStatWipe;

import java.util.function.Predicate;

public final class NetworkStatWipeExecutor implements StatWipeExecutor {
    static final String NETWORK_TARGET = "the servers behind this proxy";

    private final NetworkRoster roster;
    private final Predicate<RealtimeEnvelope> sender;

    NetworkStatWipeExecutor(NetworkRoster roster, Predicate<RealtimeEnvelope> sender) {
        this.roster = roster;
        this.sender = sender;
    }

    @Override
    public void executeStatWipe(String username, String uuid, String punishmentId, StatWipeCallback callback) {
        if (roster.hasMembers() && sender.test(push(username, uuid, punishmentId))) {
            callback.onComplete(StatWipeOutcome.DELEGATED, NETWORK_TARGET);
            return;
        }
        callback.onComplete(StatWipeOutcome.FAILED, null);
    }

    private static RealtimeEnvelope push(String username, String uuid, String punishmentId) {
        return RealtimeEnvelope.newBuilder()
                .setStatWipePush(StatWipePushEvent.newBuilder().addStatWipes(SyncPendingStatWipe.newBuilder()
                        .setMinecraftUuid(uuid)
                        .setUsername(username)
                        .setPunishmentId(punishmentId)))
                .build();
    }
}
