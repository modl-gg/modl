package gg.modl.minecraft.replay.recording;

import lombok.Value;

@Value
class TimedPacket {
    RawPacket packet;
    long time;
}
