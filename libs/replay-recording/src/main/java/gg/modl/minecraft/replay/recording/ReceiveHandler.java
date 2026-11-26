package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;

interface ReceiveHandler {
    void handle(PacketReceiveEvent event, Recording recording, long time);
}
