package gg.modl.backend.migration.service;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class ImportExclusion {
    private final ReentrantLock startLock = new ReentrantLock();

    public <T> T exclusively(Supplier<T> start) {
        startLock.lock();
        try {
            return start.get();
        } finally {
            startLock.unlock();
        }
    }
}
