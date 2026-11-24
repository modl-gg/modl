package gg.modl.backend.audit.service;

import gg.modl.backend.database.UnitOfWork;
import java.util.function.Supplier;

final class ImmediateUnitOfWork implements UnitOfWork {
    private int writes;

    int writes() {
        return writes;
    }

    @Override
    public <T> T write(Supplier<T> unit) {
        writes++;
        return unit.get();
    }

    @Override
    public void write(Runnable unit) {
        writes++;
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
        effect.run();
    }
}
