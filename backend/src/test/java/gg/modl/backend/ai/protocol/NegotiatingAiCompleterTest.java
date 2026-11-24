package gg.modl.backend.ai.protocol;

import static gg.modl.backend.ai.protocol.ProtocolFixtures.REQUEST;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.ai.model.AiCallFailure;
import gg.modl.backend.ai.model.AiCompletionRequest;
import gg.modl.backend.ai.model.AiConnection;
import gg.modl.backend.ai.model.AiDeadline;
import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiModelInfo;
import gg.modl.backend.ai.model.AiModelListing;
import gg.modl.backend.ai.model.AiOutcome;
import gg.modl.backend.ai.model.AiProtocol;
import gg.modl.backend.ai.model.AiProtocolClient;
import gg.modl.backend.ai.model.AiRequestShape;
import gg.modl.backend.ai.model.AiRequestShape.OutputMode;
import gg.modl.backend.ai.model.AiRequestShape.TokenField;
import gg.modl.backend.ai.model.AiShapeRejection;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class NegotiatingAiCompleterTest {
    private final List<Duration> sleeps = new ArrayList<>();
    private final ScriptedClient openAi = new ScriptedClient(AiProtocol.OPENAI_CHAT);
    private final AiShapeMemory memory = new AiShapeMemory();
    private Optional<AiModelInfo> metadata = Optional.empty();
    private final NegotiatingAiCompleter completer = new NegotiatingAiCompleter(
        new AiProtocolClients(List.of(openAi, new ScriptedClient(AiProtocol.ANTHROPIC_MESSAGES),
            new ScriptedClient(AiProtocol.GEMINI_GENERATE))),
        memory, connection -> metadata, sleeps::add);

    @Test
    void anOutputFormatRejectionWalksDownTheLadder() {
        openAi.reply(shape -> rejected(AiShapeRejection.OUTPUT_FORMAT), shape -> rejected(AiShapeRejection.OUTPUT_FORMAT),
            shape -> completed(shape));

        AiOutcome outcome = complete(connection("openai"));

        assertThat(openAi.shapes()).extracting(AiRequestShape::outputMode)
            .containsExactly(OutputMode.SCHEMA, OutputMode.JSON_OBJECT, OutputMode.PROMPT_ONLY);
        assertThat(outcome.shape().outputMode()).isEqualTo(OutputMode.PROMPT_ONLY);
    }

    @Test
    void theLadderEndsAtItsLastRung() {
        openAi.reply(shape -> rejected(AiShapeRejection.OUTPUT_FORMAT), shape -> rejected(AiShapeRejection.OUTPUT_FORMAT));

        AiOutcome outcome = complete(connection("deepseek"));

        assertThat(outcome).isInstanceOf(AiOutcome.Failed.class);
        assertThat(openAi.shapes()).hasSize(2);
    }

    @Test
    void theTokenFieldIsSwappedOnceAndTheEffortHintDropped() {
        openAi.reply(shape -> rejected(AiShapeRejection.TOKEN_FIELD), shape -> rejected(AiShapeRejection.EFFORT),
            shape -> rejected(AiShapeRejection.TOKEN_FIELD));

        complete(connection("openai"));

        assertThat(openAi.shapes()).extracting(AiRequestShape::tokenField)
            .containsExactly(TokenField.MAX_COMPLETION_TOKENS, TokenField.MAX_TOKENS, TokenField.MAX_TOKENS);
        assertThat(openAi.shapes()).extracting(AiRequestShape::effortHint).containsExactly(true, true, false);
    }

    @Test
    void aGenericBadRequestNeverDowngrades() {
        openAi.reply(shape -> new AiOutcome.Failed(AiCallFailure.of(AiFailureKind.INVALID_REQUEST).withStatus(400), shape));

        complete(connection("openai"));

        assertThat(openAi.shapes()).hasSize(1);
    }

    @Test
    void retryableFailuresAreRetriedTwiceWithBackoff() {
        openAi.reply(shape -> failed(AiFailureKind.RATE_LIMITED, true), shape -> failed(AiFailureKind.PROVIDER_ERROR, true),
            shape -> failed(AiFailureKind.PROVIDER_ERROR, true));

        AiOutcome outcome = complete(connection("openai"));

        assertThat(openAi.shapes()).hasSize(3);
        assertThat(sleeps).hasSize(2);
        assertThat(sleeps.get(0)).isBetween(Duration.ofMillis(1600), Duration.ofMillis(2400));
        assertThat(sleeps.get(1)).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(6));
        assertThat(((AiOutcome.Failed) outcome).failure().kind()).isEqualTo(AiFailureKind.PROVIDER_ERROR);
    }

    @Test
    void aTimeoutIsRetriedOnlyOnce() {
        openAi.reply(shape -> failed(AiFailureKind.TIMEOUT, true), shape -> failed(AiFailureKind.TIMEOUT, true),
            shape -> completed(shape));

        complete(connection("openai"));

        assertThat(openAi.shapes()).hasSize(2);
    }

    @Test
    void retryAfterIsHonouredOnlyWhenItFitsTheDeadline() {
        openAi.reply(shape -> new AiOutcome.Failed(new AiCallFailure(AiFailureKind.RATE_LIMITED, true, Duration.ofSeconds(30), 429, null),
            shape));

        completer.complete(connection("openai"), REQUEST, AiDeadline.after(Duration.ofSeconds(10)), () -> Optional.empty());

        assertThat(openAi.shapes()).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void nonRetryableFailuresStopImmediately() {
        openAi.reply(shape -> failed(AiFailureKind.AUTH, false));

        complete(connection("openai"));

        assertThat(openAi.shapes()).hasSize(1);
    }

    @Test
    void everySendAsksTheGateAndARefusalStopsTheCall() {
        AtomicInteger asked = new AtomicInteger();
        openAi.reply(shape -> failed(AiFailureKind.PROVIDER_ERROR, true), shape -> completed(shape));

        AiOutcome outcome = completer.complete(connection("openai"), REQUEST, AiDeadline.after(Duration.ofSeconds(60)),
            () -> asked.incrementAndGet() == 2 ? Optional.of(AiFailureKind.HOURLY_BUDGET) : Optional.empty());

        assertThat(asked).hasValue(2);
        assertThat(openAi.shapes()).hasSize(1);
        assertThat(((AiOutcome.Failed) outcome).failure().kind()).isEqualTo(AiFailureKind.HOURLY_BUDGET);
    }

    @Test
    void sendsAreCapped() {
        openAi.always(shape -> failed(AiFailureKind.RATE_LIMITED, true));
        openAi.reply(shape -> rejected(AiShapeRejection.EFFORT), shape -> rejected(AiShapeRejection.TOKEN_FIELD),
            shape -> rejected(AiShapeRejection.OUTPUT_FORMAT), shape -> rejected(AiShapeRejection.OUTPUT_FORMAT));

        complete(connection("openai"));

        assertThat(openAi.shapes().size()).isLessThanOrEqualTo(NegotiatingAiCompleter.MAX_SENDS);
    }

    @Test
    void theStartingShapePrefersStoredThenRememberedThenCatalogMetadata() {
        AiRequestShape stored = new AiRequestShape(OutputMode.PROMPT_ONLY, TokenField.MAX_TOKENS, false);
        AiRequestShape remembered = new AiRequestShape(OutputMode.JSON_OBJECT, TokenField.MAX_TOKENS, false);
        AiConnection plain = connection("openai");

        assertThat(completer.startingShape(plain.withShape(stored))).isEqualTo(stored);
        metadata = Optional.of(new AiModelInfo("m", null, true, false, null, false, false));
        assertThat(completer.startingShape(plain))
            .isEqualTo(new AiRequestShape(OutputMode.JSON_OBJECT, TokenField.MAX_COMPLETION_TOKENS, false));
        memory.remember(plain, remembered, Instant.EPOCH);
        assertThat(completer.startingShape(plain)).isEqualTo(remembered);
    }

    private AiOutcome complete(AiConnection connection) {
        return completer.complete(connection, REQUEST, AiDeadline.after(Duration.ofSeconds(60)), () -> Optional.empty());
    }

    private static AiConnection connection(String provider) {
        return new AiConnection(ProtocolFixtures.CATALOG.find(provider).orElseThrow(), URI.create("https://example.test/v1"), "key",
            "m", null);
    }

    private static AiOutcome completed(AiRequestShape shape) {
        return new AiOutcome.Completed("{}", shape);
    }

    private static AiOutcome rejected(AiShapeRejection rejection) {
        return new AiOutcome.Failed(new AiCallFailure(AiFailureKind.INVALID_REQUEST, false, null, 400, rejection),
            new AiRequestShape(OutputMode.SCHEMA, TokenField.MAX_TOKENS, false));
    }

    private static AiOutcome failed(AiFailureKind kind, boolean retryable) {
        return new AiOutcome.Failed(new AiCallFailure(kind, retryable, null, null, null),
            new AiRequestShape(OutputMode.SCHEMA, TokenField.MAX_TOKENS, false));
    }

    private static final class ScriptedClient implements AiProtocolClient {
        private final AiProtocol protocol;
        private final Deque<Function<AiRequestShape, AiOutcome>> replies = new ArrayDeque<>();
        private final List<AiRequestShape> shapes = new ArrayList<>();
        private Function<AiRequestShape, AiOutcome> fallback = NegotiatingAiCompleterTest::completed;

        ScriptedClient(AiProtocol protocol) {
            this.protocol = protocol;
        }

        @SafeVarargs
        final void reply(Function<AiRequestShape, AiOutcome>... scripted) {
            for (Function<AiRequestShape, AiOutcome> reply : scripted) {
                replies.add(reply);
            }
        }

        void always(Function<AiRequestShape, AiOutcome> reply) {
            fallback = reply;
        }

        List<AiRequestShape> shapes() {
            return shapes;
        }

        @Override
        public AiProtocol protocol() {
            return protocol;
        }

        @Override
        public AiModelListing listModels(AiConnection connection, AiDeadline deadline) {
            return AiModelListing.failed(AiFailureKind.LIST_UNSUPPORTED);
        }

        @Override
        public AiOutcome complete(AiConnection connection, AiCompletionRequest request, AiRequestShape shape, AiDeadline deadline) {
            shapes.add(shape);
            Function<AiRequestShape, AiOutcome> reply = replies.isEmpty() ? fallback : replies.poll();
            return reply.apply(shape);
        }
    }
}
