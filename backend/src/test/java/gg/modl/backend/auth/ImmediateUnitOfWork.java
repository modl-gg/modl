package gg.modl.backend.auth;

import gg.modl.backend.database.UnitOfWork;
import java.util.function.Supplier;

public final class ImmediateUnitOfWork implements UnitOfWork {
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
        effect.run();
    }
}
