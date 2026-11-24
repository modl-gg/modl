package gg.modl.minecraft.spigot.member;

import gg.modl.minecraft.core.network.MemberAction;
import gg.modl.minecraft.core.network.MemberActionOutcome;
import gg.modl.minecraft.core.network.link.NetworkMemberLink;
import gg.modl.minecraft.features.link.CoreLink;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

final class LinkedCoreLink implements CoreLink {
    private volatile @Nullable NetworkMemberLink link;

    void use(NetworkMemberLink link) {
        this.link = link;
    }

    @Override
    public boolean isConnected() {
        NetworkMemberLink current = link;
        return current != null && current.isConnected();
    }

    @Override
    public void send(MemberAction action, Consumer<MemberActionOutcome> onResult) {
        NetworkMemberLink current = link;
        if (current == null) {
            onResult.accept(MemberActionOutcome.NO_CORE);
            return;
        }
        current.send(action, onResult);
    }
}
