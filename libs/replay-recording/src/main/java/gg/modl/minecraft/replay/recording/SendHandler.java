package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.event.PacketSendEvent;

interface SendHandler {
    void handle(PacketSendEvent event, Recording recording, long time);
}
