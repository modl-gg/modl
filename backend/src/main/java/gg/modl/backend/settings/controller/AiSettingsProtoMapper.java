package gg.modl.backend.settings.controller;

import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.toTimestamp;

import gg.modl.backend.ai.catalog.AiModelCatalog;
import gg.modl.backend.ai.connection.AiConnectionInput;
import gg.modl.backend.ai.connection.AiDraft;
import gg.modl.backend.ai.connection.AiLimitsWrite;
import gg.modl.backend.ai.connection.AiSettingsView;
import gg.modl.backend.ai.connection.AiStatusView;
import gg.modl.backend.ai.model.AiConnectionState;
import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiModelInfo;
import gg.modl.backend.ai.model.AiModelNotice;
import gg.modl.backend.ai.model.AiProviderDefinition;
import gg.modl.backend.ai.model.AiRequestShape;
import gg.modl.backend.ai.model.AiRuntimeView;
import gg.modl.backend.ai.model.AiUsageLimits;
import gg.modl.backend.ai.probe.AiProbeResult;
import gg.modl.proto.modl.v1.AiConnectionDraft;
import gg.modl.proto.modl.v1.AiConnectionInfo;
import gg.modl.proto.modl.v1.AiEndpoint;
import gg.modl.proto.modl.v1.AiEndpointRegion;
import gg.modl.proto.modl.v1.AiFailure;
import gg.modl.proto.modl.v1.AiHealth;
import gg.modl.proto.modl.v1.AiLimits;
import gg.modl.proto.modl.v1.AiLimitsUpdate;
import gg.modl.proto.modl.v1.AiManagedFields;
import gg.modl.proto.modl.v1.AiModel;
import gg.modl.proto.modl.v1.AiModelWarning;
import gg.modl.proto.modl.v1.AiModelsResponse;
import gg.modl.proto.modl.v1.AiOutputMode;
import gg.modl.proto.modl.v1.AiProviderGroup;
import gg.modl.proto.modl.v1.AiProviderInfo;
import gg.modl.proto.modl.v1.AiReadiness;
import gg.modl.proto.modl.v1.AiSettingsResponse;
import gg.modl.proto.modl.v1.AiStatusResponse;
import gg.modl.proto.modl.v1.AiTestResponse;
import gg.modl.proto.modl.v1.SaveAiConnectionRequest;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

final class AiSettingsProtoMapper {
    private AiSettingsProtoMapper() {
    }

    static AiSettingsResponse toResponse(AiSettingsView view, AiRuntimeView runtime, Optional<AiModelNotice> notice) {
        AiSettingsResponse.Builder response = AiSettingsResponse.newBuilder()
            .setManaged(managed(view.managed()))
            .setReadiness(readiness(view.readiness()))
            .setLimits(limits(view.limits()))
            .setLimitsVersion(view.limitsVersion())
            .setHealth(health(runtime, notice));
        view.providers().forEach(provider -> response.addProviders(provider(provider)));
        view.connections().forEach(connection -> response.addConnections(connection(connection)));
        if (view.activeProvider() != null) {
            response.setActiveProvider(view.activeProvider());
        }
        return response.build();
    }

    static AiStatusResponse toResponse(AiStatusView status) {
        AiStatusResponse.Builder response = AiStatusResponse.newBuilder()
            .setReady(status.ready())
            .setReadiness(readiness(status.readiness()));
        if (status.providerName() != null) {
            response.setProviderName(status.providerName());
        }
        if (status.model() != null) {
            response.setModel(status.model());
        }
        return response.build();
    }

    static AiModelsResponse toResponse(AiModelCatalog catalog) {
        AiModelsResponse.Builder response = AiModelsResponse.newBuilder().setStale(catalog.stale());
        catalog.models().forEach(model -> response.addModels(model(model)));
        if (catalog.fetchedAt() != null) {
            response.setFetchedAt(toTimestamp(catalog.fetchedAt()));
        }
        if (catalog.failure() != null) {
            response.setFailure(failure(catalog.failure()));
        }
        return response.build();
    }

    static AiTestResponse toResponse(AiProbeResult result) {
        AiTestResponse.Builder response = AiTestResponse.newBuilder()
            .setSuccess(result.success())
            .setLatencyMs(result.latency().toMillis());
        if (result.failure() != null) {
            response.setFailure(failure(result.failure()));
        }
        if (result.outputMode() != null) {
            response.setOutputMode(outputMode(result.outputMode()));
        }
        if (result.upstreamStatus() != null) {
            response.setUpstreamStatus(result.upstreamStatus());
        }
        return response.build();
    }

    static AiConnectionInput toInput(SaveAiConnectionRequest request) {
        return new AiConnectionInput(
            request.hasBaseUrl() ? request.getBaseUrl() : null,
            request.hasApiKey() ? request.getApiKey() : null,
            request.hasModel() ? request.getModel() : null,
            request.getActivate());
    }

    static AiDraft toDraft(AiConnectionDraft draft) {
        return new AiDraft(draft.getProvider(),
            draft.hasBaseUrl() ? draft.getBaseUrl() : null,
            draft.hasApiKey() ? draft.getApiKey() : null,
            draft.hasModel() ? draft.getModel() : null);
    }

    static AiLimitsWrite toLimitsUpdate(AiLimitsUpdate update) {
        return new AiLimitsWrite(update.getExpectedVersion(), toLimits(update.getLimits()));
    }

    static AiUsageLimits toLimits(AiLimits limits) {
        return new AiUsageLimits(limits.getCallsPerHour(), limits.getCallsPerDay(), limits.getReportsPerReporterPerHour(),
            limits.getConcurrentCalls(), limits.getMaxOutputTokens());
    }

    static AiReadiness readiness(AiConnectionState.Readiness readiness) {
        return AiReadiness.valueOf("AI_READINESS_" + readiness.name());
    }

    static AiFailure failure(AiFailureKind kind) {
        return AiFailure.valueOf("AI_FAILURE_" + kind.name());
    }

    static AiOutputMode outputMode(AiRequestShape.OutputMode mode) {
        return AiOutputMode.valueOf("AI_OUTPUT_MODE_" + mode.name());
    }

    static AiProviderGroup group(AiProviderDefinition.Group group) {
        return AiProviderGroup.valueOf("AI_PROVIDER_GROUP_" + group.name());
    }

    static AiEndpointRegion region(AiProviderDefinition.Region region) {
        return AiEndpointRegion.valueOf("AI_ENDPOINT_REGION_" + region.name());
    }

    static AiModelWarning warning(AiModelNotice.Warning warning) {
        return AiModelWarning.valueOf("AI_MODEL_WARNING_" + warning.name());
    }

    private static AiProviderInfo provider(AiProviderDefinition provider) {
        AiProviderInfo.Builder info = AiProviderInfo.newBuilder()
            .setId(provider.id())
            .setDisplayName(provider.displayName())
            .setGroup(group(provider.group()))
            .setBaseUrlEditable(provider.editableBaseUrl())
            .setApiKeyRequired(provider.requiresKey());
        provider.endpoints().forEach(endpoint -> info.addEndpoints(AiEndpoint.newBuilder()
            .setRegion(region(endpoint.region()))
            .setBaseUrl(endpoint.baseUrl().toString())));
        if (provider.keyPage() != null) {
            info.setKeyPageUrl(provider.keyPage().toString());
        }
        return info.build();
    }

    private static AiConnectionInfo connection(AiSettingsView.ConnectionInfo connection) {
        AiConnectionInfo.Builder info = AiConnectionInfo.newBuilder()
            .setProvider(connection.provider())
            .setBaseUrl(connection.baseUrl())
            .setHasApiKey(connection.hasApiKey());
        if (connection.model() != null) {
            info.setModel(connection.model());
        }
        if (connection.outputMode() != null) {
            info.setOutputMode(outputMode(connection.outputMode()));
        }
        if (connection.verifiedAt() != null) {
            info.setVerifiedAt(toTimestamp(connection.verifiedAt()));
        }
        return info.build();
    }

    private static AiManagedFields managed(AiConnectionState.Managed managed) {
        return AiManagedFields.newBuilder()
            .setProvider(managed.provider())
            .setApiKey(managed.apiKey())
            .setModel(managed.model())
            .setBaseUrl(managed.baseUrl())
            .build();
    }

    private static AiLimits limits(AiUsageLimits limits) {
        return AiLimits.newBuilder()
            .setCallsPerHour(limits.callsPerHour())
            .setCallsPerDay(limits.callsPerDay())
            .setReportsPerReporterPerHour(limits.reportsPerReporterPerHour())
            .setConcurrentCalls(limits.concurrentCalls())
            .setMaxOutputTokens(limits.maxOutputTokens())
            .build();
    }

    private static AiHealth health(AiRuntimeView runtime, Optional<AiModelNotice> notice) {
        AiHealth.Builder health = AiHealth.newBuilder()
            .setCallsThisHour(runtime.callsThisHour())
            .setCallsToday(runtime.callsToday());
        setInstant(runtime.lastSuccessAt(), instant -> health.setLastSuccessAt(toTimestamp(instant)));
        setInstant(runtime.lastFailureAt(), instant -> health.setLastFailureAt(toTimestamp(instant)));
        setInstant(runtime.pausedUntil(), instant -> health.setPausedUntil(toTimestamp(instant)));
        if (runtime.lastFailure() != null) {
            health.setLastFailure(failure(runtime.lastFailure()));
        }
        notice.ifPresent(modelNotice -> {
            health.setModelWarning(warning(modelNotice.warning()));
            setInstant(modelNotice.retiresAt(), instant -> health.setModelRetiresAt(toTimestamp(instant)));
        });
        return health.build();
    }

    private static AiModel model(AiModelInfo model) {
        AiModel.Builder info = AiModel.newBuilder()
            .setId(model.id())
            .setChatCapable(model.chatCapable())
            .setDeprecated(model.deprecated());
        if (model.displayName() != null) {
            info.setDisplayName(model.displayName());
        }
        if (model.retiresAt() != null) {
            info.setRetiresAt(toTimestamp(model.retiresAt()));
        }
        if (model.structuredOutputs() != null) {
            info.setStructuredOutputs(model.structuredOutputs());
        }
        return info.build();
    }

    private static void setInstant(@Nullable Instant value, Consumer<Instant> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
