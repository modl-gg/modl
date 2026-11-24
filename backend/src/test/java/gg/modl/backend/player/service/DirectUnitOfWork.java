package gg.modl.backend.player.service;

import gg.modl.backend.database.UnitOfWork;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

final class DirectUnitOfWork implements UnitOfWork {
    private final List<Runnable> effects = new ArrayList<>();
    private int depth;
    private int committedWrites;

    @Override
    public <T> T write(Supplier<T> unit) {
        depth++;
        T result;
        try {
            result = unit.get();
        } catch (RuntimeException failure) {
            effects.clear();
            throw failure;
        } finally {
            depth--;
        }
        if (depth == 0) {
            committedWrites++;
            List<Runnable> committed = List.copyOf(effects);
            effects.clear();
            committed.forEach(Runnable::run);
        }
        return result;
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
        if (depth == 0) {
            throw new IllegalStateException("afterCommit needs a running write unit");
        }
        effects.add(effect);
    }

    boolean inWrite() {
        return depth > 0;
    }

    int committedWrites() {
        return committedWrites;
    }
}
