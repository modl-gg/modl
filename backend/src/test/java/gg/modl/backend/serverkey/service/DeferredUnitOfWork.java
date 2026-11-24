package gg.modl.backend.serverkey.service;

import gg.modl.backend.database.UnitOfWork;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

final class DeferredUnitOfWork implements UnitOfWork {
    private final List<Runnable> committed = new ArrayList<>();

    @Override
    public <T> T write(Supplier<T> unit) {
        return unit.get();
    }

    @Override
    public void write(Runnable unit) {
        unit.run();
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
        committed.add(effect);
    }

    void commit() {
        List<Runnable> effects = List.copyOf(committed);
        committed.clear();
        effects.forEach(Runnable::run);
    }
}
