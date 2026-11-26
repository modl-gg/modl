package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

final class PacketHandlers {
    private final Map<PacketTypeCommon, SendHandler> sends = new HashMap<>();
    private final Map<PacketTypeCommon, ReceiveHandler> receives = new HashMap<>();

    void onSend(PacketTypeCommon type, SendHandler handler) {
        sends.put(type, handler);
    }

    void onReceive(PacketTypeCommon type, ReceiveHandler handler) {
        receives.put(type, handler);
    }

    @Nullable SendHandler send(PacketTypeCommon type) {
        return sends.get(type);
    }

    @Nullable ReceiveHandler receive(PacketTypeCommon type) {
        return receives.get(type);
    }
}
