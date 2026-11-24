package gg.modl.backend.database;

import java.util.function.Predicate;
import java.util.function.Supplier;

public final class GeneratedIdInsert {
    public static final int DEFAULT_ATTEMPTS = 10;

    private GeneratedIdInsert() {
    }

    public static String insert(Supplier<String> idSupplier, Predicate<String> insertIfAbsent, int attempts) {
        for (int attempt = 0; attempt < attempts; attempt++) {
            String id = idSupplier.get();
            if (insertIfAbsent.test(id)) {
                return id;
            }
        }
        throw new ConcurrentUpdateException("No unused id was generated after " + attempts + " attempts");
    }
}
