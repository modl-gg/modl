package gg.modl.backend.ai.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.ai.connection.AiConnectionService;
import gg.modl.backend.ai.model.AiCallFailure;
import gg.modl.backend.ai.model.AiCompletionRequest;
import gg.modl.backend.ai.model.AiConnection;
import gg.modl.backend.ai.model.AiConnectionState;
import gg.modl.backend.ai.model.AiDeadline;
import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiModelListing;
import gg.modl.backend.ai.model.AiOutcome;
import gg.modl.backend.ai.model.AiProtocol;
import gg.modl.backend.ai.model.AiProtocolClient;
import gg.modl.backend.ai.model.AiRequestShape;
import gg.modl.backend.ai.model.AiRequestShape.OutputMode;
import gg.modl.backend.ai.model.AiRuntime;
import gg.modl.backend.ai.model.AiUsageLimits;
import gg.modl.backend.ai.protocol.AiProtocolClients;
import gg.modl.backend.ai.protocol.AiShapeMemory;
import gg.modl.backend.ai.protocol.NegotiatingAiCompleter;
import gg.modl.backend.ai.provider.AiProviderCatalog;
import gg.modl.backend.ai.service.AiAnalysisResponseParser;
import gg.modl.backend.ai.service.ChatModerationPromptBuilder;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiProbeServiceTest {
    private static final String VALID = "{\"analysis\":\"Friendly chat.\",\"confidence\":0.9,\"punishmentTypeId\":\"none\",\"severity\":\"none\"}";
    private static final AiConnection CONNECTION = new AiConnection(new AiProviderCatalog().find("openai").orElseThrow(),
        URI.create("https://api.openai.com/v1"), "key", "m", null);

    private final AiConnectionService connections = mock(AiConnectionService.class);
    private final AiRuntime runtime = mock(AiRuntime.class);
    private final AiShapeMemory memory = new AiShapeMemory();
    private final List<AiRequestShape> sent = new ArrayList<>();
    private Function<AiRequestShape, AiOutcome> reply = shape -> new AiOutcome.Completed(VALID, shape);
    private AiProbeService probe;

    @BeforeEach
    void setUp() {
        when(connections.state()).thenReturn(new AiConnectionState(AiConnectionState.Readiness.READY, CONNECTION.provider(),
            Optional.of(CONNECTION), AiConnectionState.Managed.NONE, AiUsageLimits.DEFAULTS));
        AiProtocolClient client = new AiProtocolClient() {
            @Override
            public AiProtocol protocol() {
                return AiProtocol.OPENAI_CHAT;
            }

            @Override
            public AiModelListing listModels(AiConnection connection, AiDeadline deadline) {
                return AiModelListing.failed(AiFailureKind.LIST_UNSUPPORTED);
            }

            @Override
            public AiOutcome complete(AiConnection connection, AiCompletionRequest request, AiRequestShape shape, AiDeadline deadline) {
                sent.add(shape);
                return reply.apply(shape);
            }
        };
        AiProtocolClients clients = new AiProtocolClients(List.of(client, idle(AiProtocol.ANTHROPIC_MESSAGES), idle(AiProtocol.GEMINI_GENERATE)));
        NegotiatingAiCompleter completer = new NegotiatingAiCompleter(clients, memory, connection -> Optional.empty());
        probe = new AiProbeService(connections, completer, new AiProbeSample(new ChatModerationPromptBuilder()),
            new AiAnalysisResponseParser(), memory, runtime, Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void aValidAnswerPassesAndIsRememberedForTheEffectiveConnection() {
        when(connections.isEffective(CONNECTION)).thenReturn(true);

        AiProbeResult result = probe.test(CONNECTION);

        assertThat(result.success()).isTrue();
        assertThat(result.outputMode()).isEqualTo(OutputMode.SCHEMA);
        assertThat(memory.recall(CONNECTION)).isPresent();
        verify(connections).recordShape(eq(CONNECTION), any(), eq(Instant.parse("2026-11-22T12:00:00Z")));
        verify(runtime).reset();
    }

    @Test
    void aDraftThatIsNotTheEffectiveConnectionIsOnlyRemembered() {
        AiProbeResult result = probe.test(CONNECTION);

        assertThat(result.success()).isTrue();
        assertThat(result.recordedForEffectiveConnection()).isFalse();
        verify(connections, never()).recordShape(any(), any(), any());
    }

    @Test
    void anInvalidAnswerStepsDownTheLadderEvenFromAStoredShape() {
        reply = shape -> new AiOutcome.Completed(shape.outputMode() == OutputMode.SCHEMA ? "not json" : VALID, shape);

        AiProbeResult result = probe.test(CONNECTION.withShape(new AiRequestShape(OutputMode.PROMPT_ONLY,
            AiRequestShape.TokenField.MAX_TOKENS, false)));

        assertThat(result.success()).isTrue();
        assertThat(result.outputMode()).isEqualTo(OutputMode.JSON_OBJECT);
        assertThat(sent).extracting(AiRequestShape::outputMode).containsExactly(OutputMode.SCHEMA, OutputMode.JSON_OBJECT);
    }

    @Test
    void aProviderFailureIsReportedWithItsStatus() {
        reply = shape -> new AiOutcome.Failed(AiCallFailure.of(AiFailureKind.AUTH).withStatus(401), shape);

        AiProbeResult result = probe.test(CONNECTION);

        assertThat(result.success()).isFalse();
        assertThat(result.failure()).isEqualTo(AiFailureKind.AUTH);
        assertThat(result.upstreamStatus()).isEqualTo(401);
        assertThat(sent).hasSize(1);
    }

    @Test
    void anAnswerThatNeverParsesFailsAsInvalid() {
        reply = shape -> new AiOutcome.Completed("{\"analysis\":\"x\"}", shape);

        AiProbeResult result = probe.test(CONNECTION);

        assertThat(result.failure()).isEqualTo(AiFailureKind.INVALID_RESPONSE);
        assertThat(sent).hasSize(3);
    }

    private static AiProtocolClient idle(AiProtocol protocol) {
        return new AiProtocolClient() {
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
                throw new UnsupportedOperationException();
            }
        };
    }
}
