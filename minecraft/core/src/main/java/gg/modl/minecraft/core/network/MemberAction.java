package gg.modl.minecraft.core.network;

import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

@Value
public class MemberAction {
    public static final int MAX_REQUEST_ID_LENGTH = 64;
    public static final int MAX_COMMAND_LINE_LENGTH = 256;

    String requestId;
    @Nullable MemberActionKind kind;
    @Nullable UUID actor;
    @Nullable UUID target;
    @Nullable String commandLine;
    @Nullable String memberName;
    @Nullable String memberInstanceId;
    boolean proxyCommandGranted;

    public static MemberAction playerAction(MemberActionKind kind, UUID actor, @Nullable UUID target) {
        return new MemberAction(newRequestId(), kind, actor, target, null, null, null, false);
    }

    public static MemberAction proxyCommand(String commandLine) {
        return new MemberAction(newRequestId(), MemberActionKind.PROXY_COMMAND, null, null, commandLine,
                null, null, false);
    }

    public MemberAction stampedBy(String memberName, @Nullable String memberInstanceId, boolean proxyCommandGranted) {
        return new MemberAction(requestId, kind, actor, target, commandLine, memberName, memberInstanceId,
                proxyCommandGranted);
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString();
    }
}
