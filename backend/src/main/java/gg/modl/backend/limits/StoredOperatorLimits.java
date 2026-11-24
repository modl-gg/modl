package gg.modl.backend.limits;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public record StoredOperatorLimits(Map<String, Integer> values) {
    public static final StoredOperatorLimits EMPTY = new StoredOperatorLimits(Map.of());

    public StoredOperatorLimits {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    Optional<Integer> value(OperatorLimit limit) {
        return Optional.ofNullable(values.get(limit.documentKey())).filter(limit::admits);
    }

    StoredOperatorLimits with(Map<OperatorLimit, Integer> changes) {
        Map<String, Integer> next = new LinkedHashMap<>(values);
        changes.forEach((limit, value) -> next.put(limit.documentKey(), value));
        return new StoredOperatorLimits(next);
    }
}
