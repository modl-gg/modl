package gg.modl.minecraft.features.link;

import gg.modl.minecraft.core.network.MemberAction;
import gg.modl.minecraft.core.network.MemberActionOutcome;

import java.util.function.Consumer;

public final class DisconnectedCoreLink implements CoreLink {
    public static final DisconnectedCoreLink INSTANCE = new DisconnectedCoreLink();

    private DisconnectedCoreLink() {
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public void send(MemberAction action, Consumer<MemberActionOutcome> onResult) {
        onResult.accept(MemberActionOutcome.NO_CORE);
    }
}
