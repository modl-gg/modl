package gg.modl.minecraft.core.network;

public final class MemberActionLimits {
    public static final int PER_MEMBER_PER_SECOND = 20;
    public static final int PER_ACTOR_PER_SECOND = 5;
    public static final int REPORTS_PER_MEMBER_PER_SECOND = 5;
    public static final int PROXY_COMMANDS_PER_MEMBER_PER_SECOND = 1;

    private MemberActionLimits() {
    }
}
