package gg.modl.backend.ai.protocol;

import gg.modl.backend.ai.model.AiAttemptGate;
import gg.modl.backend.ai.model.AiCallFailure;
import gg.modl.backend.ai.model.AiCompleter;
import gg.modl.backend.ai.model.AiCompletionRequest;
import gg.modl.backend.ai.model.AiConnection;
import gg.modl.backend.ai.model.AiDeadline;
import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiModelInfo;
import gg.modl.backend.ai.model.AiModelMetadata;
import gg.modl.backend.ai.model.AiOutcome;
import gg.modl.backend.ai.model.AiProtocolClient;
import gg.modl.backend.ai.model.AiProviderDefinition;
import gg.modl.backend.ai.model.AiRequestShape;
import gg.modl.backend.ai.model.AiShapeRejection;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class NegotiatingAiCompleter implements AiCompleter {
    static final int MAX_SENDS = 7;
    static final int MAX_RETRIES = 2;
    private static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(2), Duration.ofSeconds(5));
    private static final double JITTER = 0.2;

    private final AiProtocolClients clients;
    private final AiShapeMemory shapeMemory;
    private final AiModelMetadata modelMetadata;
    private final Sleeper sleeper;

    @Autowired
    public NegotiatingAiCompleter(AiProtocolClients clients, AiShapeMemory shapeMemory, AiModelMetadata modelMetadata) {
        this(clients, shapeMemory, modelMetadata, Thread::sleep);
    }

    NegotiatingAiCompleter(AiProtocolClients clients, AiShapeMemory shapeMemory, AiModelMetadata modelMetadata, Sleeper sleeper) {
        this.clients = clients;
        this.shapeMemory = shapeMemory;
        this.modelMetadata = modelMetadata;
        this.sleeper = sleeper;
    }

    @Override
    public AiOutcome complete(AiConnection connection, AiCompletionRequest request, AiDeadline deadline, AiAttemptGate gate) {
        return completeFrom(connection, request, startingShape(connection), deadline, gate);
    }

    public AiOutcome completeFrom(AiConnection connection, AiCompletionRequest request, AiRequestShape start, AiDeadline deadline,
                                  AiAttemptGate gate) {
        AiProtocolClient client = clients.forProtocol(connection.provider().protocol());
        AiRequestShape shape = start;
        Negotiation negotiation = new Negotiation();
        for (int sends = 1; ; sends++) {
            Optional<AiFailureKind> refused = gate.admit();
            if (refused.isPresent()) {
                return new AiOutcome.Failed(AiCallFailure.of(refused.get()), shape);
            }
            AiOutcome outcome = client.complete(connection, request, shape, deadline);
            if (!(outcome instanceof AiOutcome.Failed failed) || sends == MAX_SENDS) {
                return outcome;
            }
            AiCallFailure failure = failed.failure();
            if (failure.rejection() != null) {
                Optional<AiRequestShape> lowered = negotiation.lower(connection.provider(), shape, failure.rejection());
                if (lowered.isEmpty()) {
                    return outcome;
                }
                shape = lowered.get();
                continue;
            }
            Optional<Duration> delay = negotiation.retryDelay(failure, deadline);
            if (delay.isEmpty()) {
                return outcome;
            }
            try {
                sleeper.sleep(delay.get());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return outcome;
            }
        }
    }

    public AiRequestShape startingShape(AiConnection connection) {
        if (connection.shape() != null) {
            return connection.shape();
        }
        return shapeMemory.recall(connection)
            .map(AiShapeMemory.VerifiedShape::shape)
            .orElseGet(() -> catalogShape(connection));
    }

    private AiRequestShape catalogShape(AiConnection connection) {
        AiProviderDefinition provider = connection.provider();
        AiRequestShape shape = provider.initialShape();
        Optional<AiModelInfo> metadata = modelMetadata.cached(connection);
        if (metadata.isEmpty()) {
            return shape;
        }
        AiModelInfo model = metadata.get();
        if (Boolean.FALSE.equals(model.structuredOutputs()) && shape.outputMode() == AiRequestShape.OutputMode.SCHEMA) {
            shape = provider.rungBelow(shape.outputMode()).map(shape::withOutputMode).orElse(shape);
        }
        if (Boolean.FALSE.equals(model.effortHint())) {
            shape = shape.withoutEffortHint();
        }
        return shape;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final class Negotiation {
        private int retries;
        private boolean timeoutRetried;
        private boolean tokenFieldSwapped;

        Optional<AiRequestShape> lower(AiProviderDefinition provider, AiRequestShape shape, AiShapeRejection rejection) {
            return switch (rejection) {
                case OUTPUT_FORMAT -> provider.rungBelow(shape.outputMode()).map(shape::withOutputMode);
                case TOKEN_FIELD -> swapTokenField(shape);
                case EFFORT -> shape.effortHint() ? Optional.of(shape.withoutEffortHint()) : Optional.empty();
            };
        }

        Optional<Duration> retryDelay(AiCallFailure failure, AiDeadline deadline) {
            if (!failure.retryable() || retries >= MAX_RETRIES) {
                return Optional.empty();
            }
            if (failure.kind() == AiFailureKind.TIMEOUT) {
                if (timeoutRetried) {
                    return Optional.empty();
                }
                timeoutRetried = true;
            }
            Duration delay = failure.retryAfter() != null ? failure.retryAfter() : jittered(RETRY_DELAYS.get(retries));
            if (!deadline.allows(delay)) {
                return Optional.empty();
            }
            retries++;
            return Optional.of(delay);
        }

        private Optional<AiRequestShape> swapTokenField(AiRequestShape shape) {
            if (tokenFieldSwapped) {
                return Optional.empty();
            }
            tokenFieldSwapped = true;
            return Optional.of(shape.withSwappedTokenField());
        }

        private static Duration jittered(Duration base) {
            double factor = 1 + ThreadLocalRandom.current().nextDouble(-JITTER, JITTER);
            return Duration.ofMillis(Math.round(base.toMillis() * factor));
        }
    }
}
