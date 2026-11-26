package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.netty.buffer.UnpooledByteBufAllocationHelper;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.jspecify.annotations.Nullable;

final class SentPacketBytes {
    private SentPacketBytes() {
    }

    static byte @Nullable [] of(PacketSendEvent event, int maxBytes) {
        PacketWrapper<?> changed = event.needsReEncode() ? event.getLastUsedWrapper() : null;
        return changed == null ? received(event.getByteBuf(), maxBytes) : reencoded(changed, maxBytes);
    }

    private static byte @Nullable [] received(Object buffer, int maxBytes) {
        int length = ByteBufHelper.readableBytes(buffer);
        if (length <= 0 || length > maxBytes) return null;
        byte[] raw = new byte[length];
        ByteBufHelper.getBytes(buffer, ByteBufHelper.readerIndex(buffer), raw);
        return raw;
    }

    private static byte @Nullable [] reencoded(PacketWrapper<?> wrapper, int maxBytes) {
        Object original = wrapper.buffer;
        Object scratch = UnpooledByteBufAllocationHelper.buffer();
        try {
            wrapper.buffer = scratch;
            wrapper.write();
            return received(scratch, maxBytes);
        } finally {
            wrapper.buffer = original;
            ByteBufHelper.release(scratch);
        }
    }
}
