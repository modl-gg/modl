package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.network.Freeze;
import gg.modl.minecraft.core.network.MemberAction;
import gg.modl.minecraft.core.network.MemberActionKind;
import gg.modl.minecraft.core.network.MemberActionOutcome;
import gg.modl.minecraft.core.network.ModerationSnapshot;
import gg.modl.minecraft.core.network.ModerationState;
import gg.modl.minecraft.core.network.StaffSessionState;
import gg.modl.proto.modl.v1.FrozenPlayer;
import gg.modl.proto.modl.v1.MemberActionRequest;
import gg.modl.proto.modl.v1.MemberActionStatus;
import gg.modl.proto.modl.v1.ModerationStateEvent;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkProtosTest {
    private final UUID staff = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @Test
    void aSnapshotSurvivesTheWire() {
        ModerationSnapshot snapshot = new ModerationSnapshot("epoch", 7, new ModerationState(
                Collections.singletonMap(target, new Freeze(staff, 42L)),
                Collections.singletonMap(staff, new StaffSessionState(true, true, target))));

        assertEquals(snapshot, ModerationStateProtos.fromProto(ModerationStateProtos.toProto(snapshot)));
    }

    @Test
    void malformedIdsInASnapshotAreDropped() {
        ModerationSnapshot parsed = ModerationStateProtos.fromProto(ModerationStateEvent.newBuilder().setEpoch("e").setRevision(1)
                .addFrozen(FrozenPlayer.newBuilder().setMinecraftUuid("not-a-uuid").setStaffUuid(staff.toString())).build());

        assertTrue(parsed.getState().getFrozen().isEmpty());
    }

    @Test
    void everyActionKindAndOutcomeHasAWireName() {
        for (MemberActionKind kind : MemberActionKind.values()) {
            MemberAction action = kind.isPlayerAction()
                    ? MemberAction.playerAction(kind, staff, target)
                    : MemberAction.proxyCommand("stafflist");
            assertEquals(kind, MemberActionProtos.fromProto(MemberActionProtos.toProto(action)).getKind());
        }
        for (MemberActionOutcome outcome : MemberActionOutcome.values()) {
            assertEquals(outcome, MemberActionProtos.outcomeOf(MemberActionProtos.toResult("r", outcome, null).getStatus()));
        }
        assertEquals(MemberActionOutcome.INVALID, MemberActionProtos.outcomeOf(MemberActionStatus.MEMBER_ACTION_STATUS_UNSPECIFIED));
    }

    @Test
    void theHubStampedIdentityIsReadAndUnknownKindsBecomeNull() {
        MemberAction action = MemberActionProtos.fromProto(MemberActionRequest.newBuilder().setRequestId("r1")
                .setKind(gg.modl.proto.modl.v1.MemberActionKind.MEMBER_ACTION_KIND_TOGGLE_VANISH).setActorUuid(staff.toString())
                .setMemberName("lobby").setMemberInstanceId("lobby-1").setProxyCommandGranted(true).build());

        assertEquals("lobby", action.getMemberName());
        assertEquals("lobby-1", action.getMemberInstanceId());
        assertTrue(action.isProxyCommandGranted());
        assertNull(MemberActionProtos.fromProto(MemberActionRequest.newBuilder().setRequestId("r2").build()).getKind());
    }
}
