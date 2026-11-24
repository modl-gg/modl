package gg.modl.minecraft.core.command.error;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class CommandErrorCatalog {
    private final Map<Class<?>, Function<Throwable, CommandError>> explanations = new HashMap<>();

    public <T extends Throwable> void explain(Class<T> type, Function<T, CommandError> explanation) {
        explanations.put(type, failure -> explanation.apply(type.cast(failure)));
    }

    public boolean explains(Class<? extends Throwable> type) {
        return explanationFor(type).isPresent();
    }

    public CommandError errorFor(Throwable failure) {
        return explanationFor(failure.getClass())
                .map(explanation -> explanation.apply(failure))
                .orElseGet(CommandError::invalidSyntax);
    }

    private Optional<Function<Throwable, CommandError>> explanationFor(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Function<Throwable, CommandError> explanation = explanations.get(current);
            if (explanation != null) return Optional.of(explanation);
        }
        return Optional.empty();
    }
}
