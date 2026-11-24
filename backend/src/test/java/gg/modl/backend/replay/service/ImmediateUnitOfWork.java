package gg.modl.backend.replay.service;

import gg.modl.backend.database.UnitOfWork;
import java.util.function.Supplier;

final class ImmediateUnitOfWork implements UnitOfWork {
    private int writes;
    private boolean insideWrite;

    int writes() {
        return writes;
    }

    boolean insideWrite() {
        return insideWrite;
    }

    @Override
    public <T> T write(Supplier<T> unit) {
        boolean joined = insideWrite;
        if (!joined) {
            writes++;
        }
        insideWrite = true;
        try {
            return unit.get();
        } finally {
            insideWrite = joined;
        }
    }

    @Override
    public void write(Runnable unit) {
        write(() -> {
            unit.run();
            return null;
        });
    }

    @Override
    public <T> T read(Supplier<T> unit) {
        return unit.get();
    }

    @Override
    public <T> T report(Supplier<T> unit) {
        return unit.get();
    }

    @Override
    public void afterCommit(Runnable effect) {
        effect.run();
    }
}
