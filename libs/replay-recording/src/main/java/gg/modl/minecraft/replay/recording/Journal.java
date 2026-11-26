package gg.modl.minecraft.replay.recording;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

final class Journal {
    static final int BLOCK_BYTES = 16 * 1024;

    private final Deque<JournalBlock> blocks = new ArrayDeque<>();
    private final RecordingMemory memory;
    private @Nullable JournalBlock current;
    private long reservedBytes;
    private boolean closed;

    Journal(RecordingMemory memory) {
        this.memory = memory;
    }

    synchronized void append(RecordWriter record, long time) {
        if (closed) return;
        int size = record.length();
        JournalBlock block = current;
        if (block == null || !block.fits(size)) {
            if (block != null) blocks.addLast(block);
            block = new JournalBlock(Math.max(BLOCK_BYTES, size));
            reserve(block.capacity());
            current = block;
        }
        block.append(record.bytes(), size, time);
    }

    synchronized List<JournalBlock> takeClosedOlderThan(long time) {
        List<JournalBlock> taken = new ArrayList<>();
        JournalBlock oldest = blocks.peekFirst();
        while (oldest != null && oldest.lastTime() < time) {
            taken.add(release(blocks.removeFirst()));
            oldest = blocks.peekFirst();
        }
        return taken;
    }

    synchronized @Nullable JournalBlock takeOldestClosed() {
        JournalBlock oldest = blocks.pollFirst();
        return oldest == null ? null : release(oldest);
    }

    synchronized List<JournalBlock> snapshot(Set<Object> pinned) {
        List<JournalBlock> view = new ArrayList<>(blocks.size() + 1);
        view.addAll(blocks);
        pinned.addAll(blocks);
        JournalBlock block = current;
        if (block != null && !block.isEmpty()) view.add(block.frozenCopy());
        return view;
    }

    synchronized long reservedBytes() {
        return reservedBytes;
    }

    synchronized void clear() {
        closed = true;
        for (JournalBlock block : blocks) release(block);
        blocks.clear();
        JournalBlock open = current;
        current = null;
        if (open != null) {
            reservedBytes -= open.capacity();
            memory.free(open.capacity());
        }
    }

    private void reserve(int bytes) {
        reservedBytes += bytes;
        memory.charge(bytes);
    }

    private JournalBlock release(JournalBlock block) {
        reservedBytes -= block.capacity();
        memory.freeShared(block, block.capacity());
        return block;
    }
}
