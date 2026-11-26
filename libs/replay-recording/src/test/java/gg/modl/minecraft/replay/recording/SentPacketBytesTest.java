package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.exception.PacketProcessException;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.netty.buffer.UnpooledByteBufAllocationHelper;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTimeUpdate;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SentPacketBytesTest {
    private static final int MAX_BYTES = 1024;

    @Test
    void aPacketNobodyChangedIsCopiedAsItWasEncoded() throws PacketProcessException {
        TestPacketEvents.install(ServerVersion.V_1_21_11);
        byte[] encoded = bytesOf(new WrapperPlayServerTimeUpdate(100L, 6000L));
        Object buffer = bufferOf(encoded);
        try {
            assertArrayEquals(encoded, SentPacketBytes.of(event(buffer), MAX_BYTES));
        } finally {
            ByteBufHelper.release(buffer);
        }
    }

    @Test
    void aPacketAnEarlierListenerChangedThroughItsWrapperIsCopiedAsItWillBeSent() throws PacketProcessException {
        TestPacketEvents.install(ServerVersion.V_1_21_11);
        Object buffer = bufferOf(bytesOf(new WrapperPlayServerTimeUpdate(100L, 6000L)));
        try {
            PacketSendEvent event = event(buffer);
            WrapperPlayServerTimeUpdate changed = new WrapperPlayServerTimeUpdate(100L, 18000L);
            changed.buffer = buffer;
            event.setLastUsedWrapper(changed);
            event.markForReEncode(true);

            assertArrayEquals(bytesOf(new WrapperPlayServerTimeUpdate(100L, 18000L)), SentPacketBytes.of(event, MAX_BYTES));
            assertSame(buffer, changed.buffer);
        } finally {
            ByteBufHelper.release(buffer);
        }
    }

    @Test
    void aPacketOverTheLimitIsNotCopied() throws PacketProcessException {
        TestPacketEvents.install(ServerVersion.V_1_21_11);
        Object buffer = bufferOf(bytesOf(new WrapperPlayServerTimeUpdate(100L, 6000L)));
        try {
            assertNull(SentPacketBytes.of(event(buffer), 4));
        } finally {
            ByteBufHelper.release(buffer);
        }
    }

    private static PacketSendEvent event(Object buffer) throws PacketProcessException {
        User user = new User(null, ConnectionState.PLAY, ClientVersion.V_1_21_11, new UserProfile(UUID.randomUUID(), "Steve"));
        return new PacketSendEvent(0, PacketType.Play.Server.TIME_UPDATE, ServerVersion.V_1_21_11, null, user, null, buffer) {
        };
    }

    private static byte[] bytesOf(WrapperPlayServerTimeUpdate wrapper) {
        Object buffer = UnpooledByteBufAllocationHelper.buffer();
        try {
            wrapper.buffer = buffer;
            wrapper.write();
            byte[] bytes = new byte[ByteBufHelper.readableBytes(buffer)];
            ByteBufHelper.getBytes(buffer, ByteBufHelper.readerIndex(buffer), bytes);
            return bytes;
        } finally {
            ByteBufHelper.release(buffer);
        }
    }

    private static Object bufferOf(byte[] bytes) {
        Object buffer = UnpooledByteBufAllocationHelper.buffer();
        ByteBufHelper.writeBytes(buffer, bytes);
        return buffer;
    }
}
