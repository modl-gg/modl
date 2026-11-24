package gg.modl.minecraft.core.packet;

import gg.modl.minecraft.core.boot.StartupFailure;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Optional;

public final class PacketEventsRequirement {
    static final String LOGIN_MESSAGE_KEY = "api_errors.packetevents_required";

    private PacketEventsRequirement() {
    }

    public static Optional<StartupFailure> unmetBy(@Nullable String installedVersion) {
        if (PacketEventsVersion.isSupported(installedVersion)) return Optional.empty();
        return Optional.of(StartupFailure.withLoginMessage(
                PacketEventsVersion.unsupportedReason(installedVersion) + ".",
                "Install PacketEvents " + PacketEventsVersion.MINIMUM + " or newer and restart the server.",
                LOGIN_MESSAGE_KEY,
                Collections.singletonMap("version", PacketEventsVersion.MINIMUM)));
    }
}
