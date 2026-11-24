package gg.modl.backend.database;

import java.util.function.Supplier;

public interface UnitOfWork {
    <T> T write(Supplier<T> unit);

    void write(Runnable unit);

    <T> T read(Supplier<T> unit);

    <T> T report(Supplier<T> unit);

    default <T> T search(Supplier<T> unit) {
        return report(unit);
    }

    void afterCommit(Runnable effect);
}
