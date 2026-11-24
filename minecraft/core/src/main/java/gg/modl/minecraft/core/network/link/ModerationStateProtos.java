package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.network.Freeze;
import gg.modl.minecraft.core.network.ModerationSnapshot;
import gg.modl.minecraft.core.network.ModerationState;
import gg.modl.minecraft.core.network.StaffSessionState;
import gg.modl.proto.modl.v1.FrozenPlayer;
import gg.modl.proto.modl.v1.ModerationStateEvent;
import gg.modl.proto.modl.v1.StaffSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class ModerationStateProtos {
    private ModerationStateProtos() {
    }

    static ModerationStateEvent toProto(ModerationSnapshot snapshot) {
        ModerationStateEvent.Builder event = ModerationStateEvent.newBuilder()
                .setEpoch(snapshot.getEpoch())
                .setRevision(snapshot.getRevision());
        for (Map.Entry<UUID, Freeze> frozen : snapshot.getState().getFrozen().entrySet()) {
            event.addFrozen(FrozenPlayer.newBuilder()
                    .setMinecraftUuid(frozen.getKey().toString())
                    .setStaffUuid(frozen.getValue().getStaff().toString())
                    .setFrozenAt(frozen.getValue().getFrozenAtMillis()));
        }
        for (Map.Entry<UUID, StaffSessionState> staff : snapshot.getState().getStaff().entrySet()) {
            StaffSession.Builder session = StaffSession.newBuilder()
                    .setMinecraftUuid(staff.getKey().toString())
                    .setStaffMode(staff.getValue().isStaffMode())
                    .setVanished(staff.getValue().isVanished());
            UUID target = staff.getValue().getTarget();
            if (target != null) session.setTargetUuid(target.toString());
            event.addStaff(session);
        }
        return event.build();
    }

    static ModerationSnapshot fromProto(ModerationStateEvent event) {
        Map<UUID, Freeze> frozen = new LinkedHashMap<>();
        for (FrozenPlayer player : event.getFrozenList()) {
            UUID target = Uuids.parse(player.getMinecraftUuid());
            UUID staff = Uuids.parse(player.getStaffUuid());
            if (target != null && staff != null) frozen.put(target, new Freeze(staff, player.getFrozenAt()));
        }
        Map<UUID, StaffSessionState> staff = new LinkedHashMap<>();
        for (StaffSession session : event.getStaffList()) {
            UUID member = Uuids.parse(session.getMinecraftUuid());
            if (member == null) continue;
            UUID target = session.hasTargetUuid() ? Uuids.parse(session.getTargetUuid()) : null;
            staff.put(member, new StaffSessionState(session.getStaffMode(), session.getVanished(), target));
        }
        return new ModerationSnapshot(event.getEpoch(), event.getRevision(), new ModerationState(frozen, staff));
    }
}
