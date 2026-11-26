package gg.modl.minecraft.replay.recording;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.zip.Deflater;

final class DeflaterPool implements AutoCloseable {
    private static final int BUFFER_BYTES = 64 * 1024;

    private final int capacity;
    private final Deque<Deflater> idle = new ArrayDeque<>();
    private boolean closed;

    DeflaterPool(int capacity) {
        this.capacity = capacity;
    }

    byte[] deflate(byte[] raw) {
        Deflater deflater = acquire();
        try {
            deflater.setInput(raw);
            deflater.finish();
            byte[] buffer = new byte[Math.min(BUFFER_BYTES, Math.max(64, raw.length))];
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            recycle(deflater);
        }
    }

    private synchronized Deflater acquire() {
        Deflater deflater = idle.pollFirst();
        return deflater == null ? new Deflater(Deflater.BEST_SPEED) : deflater;
    }

    private void recycle(Deflater deflater) {
        synchronized (this) {
            if (!closed && idle.size() < capacity) {
                deflater.reset();
                idle.addFirst(deflater);
                return;
            }
        }
        deflater.end();
    }

    synchronized int idleCount() {
        return idle.size();
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            for (Deflater deflater : idle) deflater.end();
            idle.clear();
        }
    }
}
