package gg.modl.backend.ai.connection;

import gg.modl.backend.ai.model.AiRequestShape;
import gg.modl.backend.ai.model.AiRequestShape.OutputMode;
import gg.modl.backend.ai.model.AiRequestShape.TokenField;
import gg.modl.backend.ai.model.AiUsageLimits;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public record AiProviderSettings(
    @Nullable String activeProvider,
    Map<String, StoredConnection> connections,
    @Nullable StoredLimits limits,
    long limitsVersion
) {
    public static final AiProviderSettings EMPTY = new AiProviderSettings(null, Map.of(), null, 0L);

    public AiProviderSettings {
        connections = connections == null ? Map.of() : Map.copyOf(connections);
    }

    public Optional<StoredConnection> connection(String providerId) {
        return Optional.ofNullable(connections().get(providerId));
    }

    public AiUsageLimits effectiveLimits() {
        return limits == null ? AiUsageLimits.DEFAULTS : limits.orDefaults();
    }

    public AiProviderSettings withConnection(String providerId, StoredConnection connection) {
        Map<String, StoredConnection> next = new LinkedHashMap<>(connections());
        next.put(providerId, connection);
        return new AiProviderSettings(activeProvider, next, limits, limitsVersion);
    }

    public AiProviderSettings withoutConnection(String providerId) {
        Map<String, StoredConnection> next = new LinkedHashMap<>(connections());
        next.remove(providerId);
        return new AiProviderSettings(providerId.equals(activeProvider) ? null : activeProvider, next, limits, limitsVersion);
    }

    public AiProviderSettings withActiveProvider(@Nullable String providerId) {
        return new AiProviderSettings(providerId, connections, limits, limitsVersion);
    }

    public AiProviderSettings withLimits(AiUsageLimits usageLimits) {
        StoredLimits next = StoredLimits.of(usageLimits);
        return next.equals(limits) ? this : new AiProviderSettings(activeProvider, connections, next, limitsVersion + 1);
    }

    public record StoredConnection(
        @Nullable String baseUrl,
        @Nullable String apiKey,
        @Nullable String model,
        @Nullable StoredShape requestShape,
        @Nullable String shapeFingerprint,
        @Nullable Long verifiedAt
    ) {
        public static final StoredConnection EMPTY = new StoredConnection(null, null, null, null, null, null);

        public StoredConnection withShape(AiRequestShape shape, String fingerprint, @Nullable Long verifiedAtMillis) {
            return new StoredConnection(baseUrl, apiKey, model, StoredShape.of(shape), fingerprint,
                verifiedAtMillis != null ? verifiedAtMillis : verifiedAt);
        }

        @Override
        public String toString() {
            return "StoredConnection[baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null ? "none" : "set") + ", model=" + model + "]";
        }
    }

    public record StoredShape(
        @Nullable OutputMode outputMode,
        @Nullable TokenField tokenField,
        @Nullable Boolean effortHint
    ) {
        static StoredShape of(AiRequestShape shape) {
            return new StoredShape(shape.outputMode(), shape.tokenField(), shape.effortHint());
        }

        public Optional<AiRequestShape> toShape() {
            if (outputMode == null || tokenField == null) {
                return Optional.empty();
            }
            return Optional.of(new AiRequestShape(outputMode, tokenField, Boolean.TRUE.equals(effortHint)));
        }
    }

    public record StoredLimits(
        @Nullable Integer callsPerHour,
        @Nullable Integer callsPerDay,
        @Nullable Integer reportsPerReporterPerHour,
        @Nullable Integer concurrentCalls,
        @Nullable Integer maxOutputTokens
    ) {
        static StoredLimits of(AiUsageLimits limits) {
            return new StoredLimits(limits.callsPerHour(), limits.callsPerDay(), limits.reportsPerReporterPerHour(),
                limits.concurrentCalls(), limits.maxOutputTokens());
        }

        AiUsageLimits orDefaults() {
            AiUsageLimits defaults = AiUsageLimits.DEFAULTS;
            AiUsageLimits merged = new AiUsageLimits(
                callsPerHour != null ? callsPerHour : defaults.callsPerHour(),
                callsPerDay != null ? callsPerDay : defaults.callsPerDay(),
                reportsPerReporterPerHour != null ? reportsPerReporterPerHour : defaults.reportsPerReporterPerHour(),
                concurrentCalls != null ? concurrentCalls : defaults.concurrentCalls(),
                maxOutputTokens != null ? maxOutputTokens : defaults.maxOutputTokens());
            return merged.withinBounds() ? merged : defaults;
        }
    }
}
