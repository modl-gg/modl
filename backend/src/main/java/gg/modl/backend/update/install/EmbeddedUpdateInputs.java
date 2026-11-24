package gg.modl.backend.update.install;

import gg.modl.update.EmbeddedUpdateContract;
import gg.modl.update.HostDescriptor;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

public record EmbeddedUpdateInputs(
    Function<String, CompletionStage<Void>> prepare,
    Runnable restart,
    Consumer<String> phase,
    HostDescriptor host
) {
    @SuppressWarnings("unchecked")
    public static Optional<EmbeddedUpdateInputs> from(Map<String, Object> input) {
        if (input.get(EmbeddedUpdateContract.PREPARE) instanceof Function<?, ?> prepare
            && input.get(EmbeddedUpdateContract.RESTART) instanceof Runnable restart
            && input.get(EmbeddedUpdateContract.PHASE) instanceof Consumer<?> phase
            && input.get(EmbeddedUpdateContract.HOST) instanceof String host) {
            HostDescriptor descriptor = HostDescriptor.parse(host);
            if (descriptor != null) {
                return Optional.of(new EmbeddedUpdateInputs((Function<String, CompletionStage<Void>>) prepare, restart,
                    (Consumer<String>) phase, descriptor));
            }
        }
        return Optional.empty();
    }
}
