package gg.modl.backend.ai.protocol;

import gg.modl.backend.ai.http.AiHttpResponse;
import gg.modl.backend.ai.model.AiCallFailure;
import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiShapeRejection;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

public final class AiErrorClassifier {
    private static final Set<String> AUTH_SIGNALS = Set.of("api_key_invalid", "invalid_api_key", "authentication_error");
    private static final Set<String> QUOTA_SIGNALS = Set.of(
        "insufficient_quota", "credit_balance_exhausted", "organization_spend_limit_exceeded", "project_spend_limit_exceeded",
        "organization_usage_limit_exceeded", "arrearage", "1113", "billing_error");
    private static final Set<String> FILTER_SIGNALS = Set.of("data_inspection_failed", "content_filter", "moderation");
    private static final Set<String> MODEL_SIGNALS = Set.of("model_not_found");
    private static final int MINIMAX_RATE_LIMITED = 1002;
    private static final int MINIMAX_AUTH = 1004;
    private static final int MINIMAX_BALANCE = 1008;
    private static final int MINIMAX_FILTERED = 1027;
    private static final int MINIMAX_TOKEN_LIMIT = 1039;
    private static final Set<Integer> MINIMAX_TRANSIENT = Set.of(1000, 1001, 1013);
    private static final int OVERLOADED = 529;
    private static final Pattern RETRY_AFTER_SECONDS = Pattern.compile("[0-9]{1,6}");

    private AiErrorClassifier() {
    }

    public static AiCallFailure classify(AiHttpResponse response) {
        return classify(response.status(), response.header("retry-after").orElse(null),
            response.header("x-should-retry").orElse(null), AiJson.parse(response.body()).orElse(null));
    }

    public static AiCallFailure classify(int status, @Nullable String retryAfter, @Nullable String shouldRetry, @Nullable JsonNode body) {
        Set<String> signals = signals(body);
        boolean retryForbidden = "false".equalsIgnoreCase(shouldRetry);
        Duration delay = retryAfter(retryAfter);
        AiCallFailure failure = kindOf(status, signals, retryForbidden, body);
        return new AiCallFailure(failure.kind(), failure.retryable(), failure.retryable() ? delay : null, status, failure.rejection());
    }

    public static AiCallFailure embeddedError(JsonNode error) {
        JsonNode code = error.path("code");
        if (code.isIntegralNumber() && code.intValue() >= 400 && code.intValue() < 600) {
            return classify(code.intValue(), null, null, AiJson.object().set("error", error));
        }
        return AiCallFailure.retryable(AiFailureKind.PROVIDER_ERROR);
    }

    public static AiCallFailure minimaxStatus(int code) {
        if (code == MINIMAX_AUTH) {
            return AiCallFailure.of(AiFailureKind.AUTH);
        }
        if (code == MINIMAX_BALANCE) {
            return AiCallFailure.of(AiFailureKind.QUOTA);
        }
        if (code == MINIMAX_FILTERED) {
            return AiCallFailure.of(AiFailureKind.FILTERED);
        }
        if (code == MINIMAX_TOKEN_LIMIT) {
            return AiCallFailure.of(AiFailureKind.TRUNCATED);
        }
        if (code == MINIMAX_RATE_LIMITED) {
            return AiCallFailure.retryable(AiFailureKind.RATE_LIMITED);
        }
        if (MINIMAX_TRANSIENT.contains(code)) {
            return AiCallFailure.retryable(AiFailureKind.PROVIDER_ERROR);
        }
        return AiCallFailure.of(AiFailureKind.PROVIDER_ERROR);
    }

    public static AiCallFailure transport(AiFailureKind kind) {
        return switch (kind) {
            case CONNECT, TIMEOUT -> AiCallFailure.retryable(kind);
            default -> AiCallFailure.of(kind);
        };
    }

    private static AiCallFailure kindOf(int status, Set<String> signals, boolean retryForbidden, @Nullable JsonNode body) {
        if (status == 401 || containsAny(signals, AUTH_SIGNALS)) {
            return AiCallFailure.of(AiFailureKind.AUTH);
        }
        if (status == 402 || containsAny(signals, QUOTA_SIGNALS) || (status == 429 && retryForbidden)) {
            return AiCallFailure.of(AiFailureKind.QUOTA);
        }
        if (containsAny(signals, FILTER_SIGNALS) || (status == 403 && hasModerationMetadata(body))) {
            return AiCallFailure.of(AiFailureKind.FILTERED);
        }
        if (status == 403) {
            return AiCallFailure.of(AiFailureKind.PERMISSION);
        }
        AiShapeRejection rejection = AiShapeRejections.detect(status, body);
        if (rejection != null) {
            return new AiCallFailure(AiFailureKind.INVALID_REQUEST, false, null, status, rejection);
        }
        if (status == 404 || containsAny(signals, MODEL_SIGNALS)) {
            return AiCallFailure.of(AiFailureKind.MODEL_NOT_FOUND);
        }
        if (status == 408) {
            return AiCallFailure.retryable(AiFailureKind.TIMEOUT);
        }
        if (status == 429) {
            return AiCallFailure.retryable(AiFailureKind.RATE_LIMITED);
        }
        if (status >= 500 || status == OVERLOADED) {
            return retryForbidden ? AiCallFailure.of(AiFailureKind.PROVIDER_ERROR) : AiCallFailure.retryable(AiFailureKind.PROVIDER_ERROR);
        }
        if (status >= 300 && status < 400) {
            return AiCallFailure.of(AiFailureKind.REDIRECT);
        }
        if (status >= 400) {
            return AiCallFailure.of(AiFailureKind.INVALID_REQUEST);
        }
        return AiCallFailure.of(AiFailureKind.PROVIDER_ERROR);
    }

    private static Set<String> signals(@Nullable JsonNode body) {
        Set<String> signals = new HashSet<>();
        if (body == null) {
            return signals;
        }
        JsonNode error = body.path("error");
        addSignal(signals, error.path("code"));
        addSignal(signals, error.path("type"));
        addSignal(signals, error.path("status"));
        addSignal(signals, body.path("code"));
        for (JsonNode detail : error.path("details").values()) {
            addSignal(signals, detail.path("reason"));
        }
        return signals;
    }

    private static void addSignal(Set<String> signals, JsonNode value) {
        String scalar = AiJson.scalar(value);
        if (scalar != null && !scalar.isBlank()) {
            signals.add(scalar.toLowerCase(Locale.ROOT));
        }
    }

    private static boolean hasModerationMetadata(@Nullable JsonNode body) {
        if (body == null) {
            return false;
        }
        JsonNode metadata = body.path("error").path("metadata");
        return metadata.has("reasons") || metadata.has("flagged_input");
    }

    private static boolean containsAny(Set<String> signals, Set<String> wanted) {
        return signals.stream().anyMatch(wanted::contains);
    }

    @Nullable
    static Duration retryAfter(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        return RETRY_AFTER_SECONDS.matcher(value).matches() ? Duration.ofSeconds(Long.parseLong(value)) : null;
    }
}
