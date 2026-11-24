package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.network.MemberAction;
import gg.modl.minecraft.core.network.MemberActionKind;
import gg.modl.minecraft.core.network.MemberActionOutcome;
import gg.modl.proto.modl.v1.MemberActionRequest;
import gg.modl.proto.modl.v1.MemberActionResult;
import gg.modl.proto.modl.v1.MemberActionStatus;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

final class MemberActionProtos {
    private static final Map<MemberActionKind, gg.modl.proto.modl.v1.MemberActionKind> KINDS = kinds();
    private static final Map<MemberActionOutcome, MemberActionStatus> STATUSES = statuses();

    private MemberActionProtos() {
    }

    static MemberActionRequest toProto(MemberAction action) {
        MemberActionRequest.Builder request = MemberActionRequest.newBuilder()
                .setRequestId(action.getRequestId())
                .setKind(protoKindOf(action.getKind()));
        UUID actor = action.getActor();
        if (actor != null) request.setActorUuid(actor.toString());
        UUID target = action.getTarget();
        if (target != null) request.setTargetUuid(target.toString());
        String commandLine = action.getCommandLine();
        if (commandLine != null) request.setCommandLine(commandLine);
        return request.build();
    }

    static MemberAction fromProto(MemberActionRequest request) {
        UUID actor = request.hasActorUuid() ? Uuids.parse(request.getActorUuid()) : null;
        UUID target = request.hasTargetUuid() ? Uuids.parse(request.getTargetUuid()) : null;
        String commandLine = request.hasCommandLine() ? request.getCommandLine() : null;
        return new MemberAction(request.getRequestId(), kindOf(request.getKind()), actor, target, commandLine,
                request.getMemberName(), request.getMemberInstanceId(), request.getProxyCommandGranted());
    }

    static MemberActionResult toResult(String requestId, MemberActionOutcome outcome, @Nullable UUID actor) {
        MemberActionStatus status = STATUSES.get(outcome);
        if (status == null) throw new IllegalStateException("No wire status is mapped for " + outcome);
        MemberActionResult.Builder result = MemberActionResult.newBuilder()
                .setRequestId(requestId)
                .setStatus(status);
        if (actor != null) result.setActorUuid(actor.toString());
        return result.build();
    }

    static MemberActionOutcome outcomeOf(MemberActionStatus status) {
        for (Map.Entry<MemberActionOutcome, MemberActionStatus> entry : STATUSES.entrySet()) {
            if (entry.getValue() == status) return entry.getKey();
        }
        return MemberActionOutcome.INVALID;
    }

    private static gg.modl.proto.modl.v1.MemberActionKind protoKindOf(@Nullable MemberActionKind kind) {
        gg.modl.proto.modl.v1.MemberActionKind mapped = kind == null ? null : KINDS.get(kind);
        if (mapped == null) throw new IllegalStateException("A member action without a known kind cannot be sent");
        return mapped;
    }

    private static @Nullable MemberActionKind kindOf(gg.modl.proto.modl.v1.MemberActionKind kind) {
        for (Map.Entry<MemberActionKind, gg.modl.proto.modl.v1.MemberActionKind> entry : KINDS.entrySet()) {
            if (entry.getValue() == kind) return entry.getKey();
        }
        return null;
    }

    private static Map<MemberActionKind, gg.modl.proto.modl.v1.MemberActionKind> kinds() {
        Map<MemberActionKind, gg.modl.proto.modl.v1.MemberActionKind> kinds = new EnumMap<>(MemberActionKind.class);
        for (MemberActionKind kind : MemberActionKind.values()) {
            kinds.put(kind, gg.modl.proto.modl.v1.MemberActionKind.valueOf("MEMBER_ACTION_KIND_" + kind.name()));
        }
        return kinds;
    }

    private static Map<MemberActionOutcome, MemberActionStatus> statuses() {
        Map<MemberActionOutcome, MemberActionStatus> statuses = new EnumMap<>(MemberActionOutcome.class);
        for (MemberActionOutcome outcome : MemberActionOutcome.values()) {
            statuses.put(outcome, MemberActionStatus.valueOf("MEMBER_ACTION_STATUS_" + outcome.name()));
        }
        return statuses;
    }
}
