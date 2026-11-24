package gg.modl.minecraft.core.network;

import java.util.function.Consumer;

public interface MemberActionSender {
    void send(MemberAction action, Consumer<MemberActionOutcome> onResult);
}
