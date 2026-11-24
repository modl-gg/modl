package gg.modl.backend.database;

import gg.modl.backend.infrastructure.logging.OperatorNotice;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DiskFullNotice {
    public static final String MESSAGE = "The disk that holds the modl database is full, so modl cannot save changes. "
        + "Free some disk space; modl saves again once there is room.";

    private final OperatorNotice notice;
    private final AtomicBoolean published = new AtomicBoolean();

    public DiskFullNotice(OperatorNotice notice) {
        this.notice = notice;
    }

    public void report() {
        if (published.compareAndSet(false, true)) {
            notice.publish(MESSAGE);
        }
    }
}
