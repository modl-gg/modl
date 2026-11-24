package gg.modl.minecraft.features.link;

import gg.modl.minecraft.core.network.MemberActionSender;

public interface CoreLink extends MemberActionSender {
    boolean isConnected();
}
