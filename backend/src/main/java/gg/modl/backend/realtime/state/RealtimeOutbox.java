package gg.modl.backend.realtime.state;

import java.util.LinkedList;
import java.util.ListIterator;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;

public final class RealtimeOutbox {
    private final int maxMessages;
    private final long maxBytes;
    private final ReentrantLock lock = new ReentrantLock();
    @SuppressWarnings("JdkObsolete")
    private final LinkedList<Outbound> queue = new LinkedList<>();
    private long queuedBytes;
    private boolean draining;
    private boolean closed;

    public RealtimeOutbox(int maxMessages, long maxBytes) {
        this.maxMessages = maxMessages;
        this.maxBytes = maxBytes;
    }

    public Admission offer(BinaryMessage message) {
        return admit(new Send(message, null));
    }

    public Admission offerLatest(String slot, BinaryMessage message) {
        lock.lock();
        try {
            if (!closed && replaceQueued(slot, message)) {
                return Admission.QUEUED;
            }
            return admit(new Send(message, slot));
        } finally {
            lock.unlock();
        }
    }

    public Admission offerThenClose(BinaryMessage message, CloseStatus status, String reason) {
        lock.lock();
        try {
            Admission admission = admit(new Send(message, null));
            if (admission.isAccepted()) {
                queue.addLast(new Close(status, reason));
                closed = true;
            }
            return admission;
        } finally {
            lock.unlock();
        }
    }

    @Nullable
    public Outbound next() {
        lock.lock();
        try {
            Outbound next = queue.pollFirst();
            if (next == null) {
                draining = false;
                return null;
            }
            queuedBytes -= next.size();
            return next;
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        lock.lock();
        try {
            closed = true;
            queue.clear();
            queuedBytes = 0;
        } finally {
            lock.unlock();
        }
    }

    public int queuedMessages() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    private boolean replaceQueued(String slot, BinaryMessage message) {
        for (ListIterator<Outbound> iterator = queue.listIterator(); iterator.hasNext(); ) {
            Outbound queued = iterator.next();
            if (queued instanceof Send send && send.fills(slot)) {
                long grown = message.getPayloadLength() - queued.size();
                if (queuedBytes + grown > maxBytes) {
                    return false;
                }
                iterator.set(new Send(message, slot));
                queuedBytes += grown;
                return true;
            }
        }
        return false;
    }

    private Admission admit(Outbound outbound) {
        lock.lock();
        try {
            if (closed) {
                return Admission.CLOSED;
            }
            if (queue.size() >= maxMessages || queuedBytes + outbound.size() > maxBytes) {
                return Admission.FULL;
            }
            queue.addLast(outbound);
            queuedBytes += outbound.size();
            if (draining) {
                return Admission.QUEUED;
            }
            draining = true;
            return Admission.STARTS_DRAIN;
        } finally {
            lock.unlock();
        }
    }

    public enum Admission {
        STARTS_DRAIN,
        QUEUED,
        FULL,
        CLOSED;

        public boolean isAccepted() {
            return this == STARTS_DRAIN || this == QUEUED;
        }
    }

    public sealed interface Outbound permits Send, Close {
        long size();
    }

    public record Send(BinaryMessage message, @Nullable String slot) implements Outbound {
        @Override
        public long size() {
            return message.getPayloadLength();
        }

        boolean fills(String latestSlot) {
            return latestSlot.equals(slot);
        }
    }

    public record Close(CloseStatus status, String reason) implements Outbound {
        @Override
        public long size() {
            return 0;
        }
    }
}
