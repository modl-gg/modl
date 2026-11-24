package gg.modl.backend.database;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

public final class InClauseChunks {
    public static final int CHUNK_SIZE = 500;

    private InClauseChunks() {
    }

    public static <T> List<List<T>> of(Collection<? extends T> values) {
        List<T> distinct = new ArrayList<>(new LinkedHashSet<>(values));
        List<List<T>> chunks = new ArrayList<>();
        for (int start = 0; start < distinct.size(); start += CHUNK_SIZE) {
            chunks.add(List.copyOf(distinct.subList(start, Math.min(start + CHUNK_SIZE, distinct.size()))));
        }
        return chunks;
    }

    public static <T, R> List<R> collect(Collection<? extends T> values, Function<List<T>, ? extends Collection<R>> query) {
        List<R> results = new ArrayList<>();
        for (List<T> chunk : InClauseChunks.<T>of(values)) {
            results.addAll(query.apply(chunk));
        }
        return results;
    }

    public static <T> int sum(Collection<? extends T> values, Function<List<T>, Integer> statement) {
        int total = 0;
        for (List<T> chunk : InClauseChunks.<T>of(values)) {
            total += statement.apply(chunk);
        }
        return total;
    }
}
