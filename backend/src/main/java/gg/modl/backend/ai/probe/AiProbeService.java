package gg.modl.backend.ai.probe;

import gg.modl.backend.ai.connection.AiConnectionService;
import gg.modl.backend.ai.model.AiAttemptGate;
import gg.modl.backend.ai.model.AiCompletionRequest;
import gg.modl.backend.ai.model.AiConnection;
import gg.modl.backend.ai.model.AiDeadline;
import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiOutcome;
import gg.modl.backend.ai.model.AiRequestShape;
import gg.modl.backend.ai.model.AiRuntime;
import gg.modl.backend.ai.protocol.AiShapeMemory;
import gg.modl.backend.ai.protocol.NegotiatingAiCompleter;
import gg.modl.backend.ai.service.AiAnalysisResponseParser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

@Service
public class AiProbeService {
    static final Duration PROBE_DEADLINE = Duration.ofSeconds(45);
    static final int CONCURRENT_PROBES = 2;

    private final AiConnectionService connections;
    private final NegotiatingAiCompleter completer;
    private final AiProbeSample sample;
    private final AiAnalysisResponseParser parser;
    private final AiShapeMemory shapeMemory;
    private final AiRuntime runtime;
    private final Clock clock;
    private final Semaphore probes = new Semaphore(CONCURRENT_PROBES);

    public AiProbeService(AiConnectionService connections, NegotiatingAiCompleter completer, AiProbeSample sample,
                          AiAnalysisResponseParser parser, AiShapeMemory shapeMemory, AiRuntime runtime, Clock clock) {
        this.connections = connections;
        this.completer = completer;
        this.sample = sample;
        this.parser = parser;
        this.shapeMemory = shapeMemory;
        this.runtime = runtime;
        this.clock = clock;
    }

    public AiProbeResult test(AiConnection connection) {
        if (!probes.tryAcquire()) {
            return new AiProbeResult(false, AiFailureKind.BUSY, null, Duration.ZERO, null, false);
        }
        long started = System.nanoTime();
        try {
            return probe(connection, started);
        } finally {
            probes.release();
        }
    }

    private AiProbeResult probe(AiConnection connection, long started) {
        AiCompletionRequest request = sample.request(connections.state().limits().maxOutputTokens());
        AiDeadline deadline = AiDeadline.after(PROBE_DEADLINE);
        AiRequestShape shape = connection.provider().initialShape();
        while (true) {
            AiOutcome outcome = completer.completeFrom(connection, request, shape, deadline, AiAttemptGate.UNMETERED);
            Optional<AiFailureKind> failure = failureOf(outcome);
            if (failure.isEmpty()) {
                return succeeded(connection, outcome.shape(), started);
            }
            Optional<AiRequestShape.OutputMode> lower = failure.get() == AiFailureKind.INVALID_RESPONSE
                ? connection.provider().rungBelow(outcome.shape().outputMode()) : Optional.empty();
            if (lower.isEmpty() || deadline.expired()) {
                Integer status = outcome instanceof AiOutcome.Failed failed ? failed.failure().status() : null;
                return new AiProbeResult(false, failure.get(), outcome.shape().outputMode(), elapsed(started), status, false);
            }
            shape = outcome.shape().withOutputMode(lower.get());
        }
    }

    private Optional<AiFailureKind> failureOf(AiOutcome outcome) {
        return switch (outcome) {
            case AiOutcome.Failed failed -> Optional.of(failed.failure().kind());
            case AiOutcome.Completed completed -> parser.parse(completed.text(), sample.typeIds(), clock.instant()).isPresent()
                ? Optional.empty() : Optional.of(AiFailureKind.INVALID_RESPONSE);
        };
    }

    private AiProbeResult succeeded(AiConnection connection, AiRequestShape shape, long started) {
        Instant now = clock.instant();
        shapeMemory.remember(connection, shape, now);
        boolean effective = connections.isEffective(connection);
        if (effective) {
            connections.recordShape(connection, shape, now);
            runtime.reset();
        }
        return new AiProbeResult(true, null, shape.outputMode(), elapsed(started), null, effective);
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }
}
