package gg.modl.backend.settings.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class FieldChanges<T> {
    private static final String SEPARATOR = ", ";

    private final String summaryPrefix;
    private final List<Field<T>> fields;

    private FieldChanges(String summaryPrefix, List<Field<T>> fields) {
        this.summaryPrefix = summaryPrefix;
        this.fields = List.copyOf(fields);
    }

    public static <T> Builder<T> summarizedAs(String summaryPrefix) {
        return new Builder<>(summaryPrefix);
    }

    public List<String> between(T previous, T next) {
        return fields.stream()
            .filter(field -> !Objects.equals(field.value().apply(previous), field.value().apply(next)))
            .map(Field::label)
            .toList();
    }

    public Optional<String> summary(T previous, T next) {
        return summarize(between(previous, next));
    }

    public Optional<String> summarize(List<String> changed) {
        return changed.isEmpty() ? Optional.empty() : Optional.of(summaryPrefix + String.join(SEPARATOR, changed));
    }

    public static final class Builder<T> {
        private final String summaryPrefix;
        private final List<Field<T>> fields = new ArrayList<>();

        private Builder(String summaryPrefix) {
            this.summaryPrefix = summaryPrefix;
        }

        public Builder<T> compare(String label, Function<T, Object> value) {
            fields.add(new Field<>(label, value));
            return this;
        }

        public FieldChanges<T> build() {
            return new FieldChanges<>(summaryPrefix, fields);
        }
    }

    private record Field<T>(String label, Function<T, Object> value) {
    }
}
