package gg.modl.minecraft.replay.recording;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DeflaterPoolTest {
    @Test
    void pooledDeflatersAreReusedAndEndedOnClose() throws DataFormatException {
        DeflaterPool pool = new DeflaterPool(1);
        byte[] raw = new byte[10_000];
        Arrays.fill(raw, (byte) 7);

        RawPacket packet = RawPacket.compress(RawPacket.CHUNK_DATA, raw, pool);
        assertArrayEquals(raw, packet.inflate());
        assertEquals(1, pool.idleCount());

        pool.close();
        assertEquals(0, pool.idleCount());
        assertArrayEquals(raw, RawPacket.compress(RawPacket.CHUNK_DATA, raw, pool).inflate());
        assertEquals(0, pool.idleCount());
    }
}
