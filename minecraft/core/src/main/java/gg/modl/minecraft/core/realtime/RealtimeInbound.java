package gg.modl.minecraft.core.realtime;

import gg.modl.proto.modl.v1.RealtimeEnvelope;

public interface RealtimeInbound {
    void receive(RealtimeEnvelope envelope);
}
