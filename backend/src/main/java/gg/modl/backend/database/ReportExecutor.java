package gg.modl.backend.database;

import java.util.function.Supplier;

@FunctionalInterface
public interface ReportExecutor {
    <T> T run(Supplier<T> report);
}
