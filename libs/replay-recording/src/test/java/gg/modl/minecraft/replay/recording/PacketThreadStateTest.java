package gg.modl.minecraft.replay.recording;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class PacketThreadStateTest {
    private static final List<Class<?>> PACKET_THREAD_CLASSES = Arrays.asList(RecordEncoder.class, RecordWriter.class,
            RawPacket.class, DeflaterPool.class, ChunkIngest.class, SentPacketBytes.class, PacketTap.class);

    @Test
    void noClassThatRunsOnPacketThreadsKeepsThreadLocalState() {
        for (Class<?> type : PACKET_THREAD_CLASSES) {
            for (Field field : type.getDeclaredFields()) {
                assertFalse(Modifier.isStatic(field.getModifiers()) && ThreadLocal.class.isAssignableFrom(field.getType()),
                        type.getSimpleName() + "." + field.getName());
            }
        }
    }

    @Test
    void everyRecordIsEncodedIntoItsOwnWriter() {
        RecordWriter first = RecordEncoder.entityHead(1, 7, 90F);
        RecordWriter second = RecordEncoder.entityHead(2, 8, 45F);

        assertNotSame(first, second);
        assertEquals(first.length(), second.length());
        assertFalse(Arrays.equals(Arrays.copyOf(first.bytes(), first.length()), Arrays.copyOf(second.bytes(), second.length())));
    }
}
