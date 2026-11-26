package gg.modl.minecraft.replay.recording;

final class JournalBlock {
    private final byte[] data;
    private int length;
    private long firstTime = Long.MAX_VALUE;
    private long lastTime = Long.MIN_VALUE;

    JournalBlock(int capacity) {
        this.data = new byte[capacity];
    }

    private JournalBlock(byte[] data, int length, long firstTime, long lastTime) {
        this.data = data;
        this.length = length;
        this.firstTime = firstTime;
        this.lastTime = lastTime;
    }

    boolean fits(int size) {
        return length + size <= data.length;
    }

    void append(byte[] source, int size, long time) {
        System.arraycopy(source, 0, data, length, size);
        length += size;
        if (time < firstTime) firstTime = time;
        if (time > lastTime) lastTime = time;
    }

    int capacity() {
        return data.length;
    }

    long lastTime() {
        return lastTime;
    }

    long firstTime() {
        return firstTime;
    }

    boolean isEmpty() {
        return length == 0;
    }

    JournalBlock frozenCopy() {
        byte[] copy = new byte[length];
        System.arraycopy(data, 0, copy, 0, length);
        return new JournalBlock(copy, length, firstTime, lastTime);
    }

    void replay(RecordVisitor visitor) {
        RecordReader reader = new RecordReader(data, 0, length);
        while (reader.hasMore()) {
            Records.decode(reader, visitor);
        }
    }
}
