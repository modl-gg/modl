package gg.modl.minecraft.core.member;

public interface MemberSessionListener {
    void onConnected(MemberSession session);

    void onDisconnected(MemberSession session);

    void onDegraded(String reason);
}
