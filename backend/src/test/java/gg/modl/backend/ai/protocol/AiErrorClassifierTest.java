package gg.modl.backend.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.ai.model.AiCallFailure;
import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiShapeRejection;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AiErrorClassifierTest {

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "401 | {\"error\":{\"message\":\"Incorrect API key\",\"type\":\"invalid_request_error\"}} | AUTH | false",
        "400 | {\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}} | AUTH | false",
        "403 | {\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\"}} | PERMISSION | false",
        "402 | {\"error\":{\"message\":\"Insufficient credits\",\"code\":402}} | QUOTA | false",
        "429 | {\"error\":{\"code\":\"insufficient_quota\",\"type\":\"insufficient_quota\"}} | QUOTA | false",
        "429 | {\"error\":{\"code\":\"organization_spend_limit_exceeded\"}} | QUOTA | false",
        "403 | {\"error\":{\"code\":\"Arrearage\",\"message\":\"Access denied\"}} | QUOTA | false",
        "429 | {\"error\":{\"code\":\"1113\",\"message\":\"balance\"}} | QUOTA | false",
        "400 | {\"type\":\"error\",\"error\":{\"type\":\"billing_error\",\"message\":\"credit\"}} | QUOTA | false",
        "400 | {\"error\":{\"code\":\"data_inspection_failed\",\"message\":\"inappropriate\"}} | FILTERED | false",
        "403 | {\"error\":{\"code\":403,\"message\":\"flagged\",\"metadata\":{\"reasons\":[\"violence\"],\"flagged_input\":\"x\"}}} | FILTERED | false",
        "404 | {\"error\":{\"code\":\"model_not_found\",\"message\":\"The model does not exist\"}} | MODEL_NOT_FOUND | false",
        "408 | {} | TIMEOUT | true",
        "429 | {\"error\":{\"type\":\"rate_limit_error\"}} | RATE_LIMITED | true",
        "500 | {} | PROVIDER_ERROR | true",
        "503 | {} | PROVIDER_ERROR | true",
        "529 | {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}} | PROVIDER_ERROR | true",
        "302 | {} | REDIRECT | false",
        "400 | {\"error\":{\"message\":\"This model's maximum context length is 8192 tokens\"}} | INVALID_REQUEST | false",
        "422 | {} | INVALID_REQUEST | false"
    })
    void statusesAndStructuredFieldsMapToKinds(int status, String body, AiFailureKind kind, boolean retryable) {
        AiCallFailure failure = AiErrorClassifier.classify(status, null, null, AiJson.parse(body).orElse(null));

        assertThat(failure.kind()).isEqualTo(kind);
        assertThat(failure.retryable()).isEqualTo(retryable);
        assertThat(failure.status()).isEqualTo(status);
    }

    @Test
    void anthropicSpendCapsAreNotRetried() {
        assertThat(AiErrorClassifier.classify(429, "30", "false", null).kind()).isEqualTo(AiFailureKind.QUOTA);
        assertThat(AiErrorClassifier.classify(500, null, "false", null).retryable()).isFalse();
    }

    @Test
    void retryAfterIsCarriedForRetryableFailures() {
        AiCallFailure failure = AiErrorClassifier.classify(429, "7", null, null);

        assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(7));
        assertThat(AiErrorClassifier.classify(401, "7", null, null).retryAfter()).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "400 | {\"error\":{\"message\":\"Invalid parameter: 'response_format' of type 'json_schema' is not supported\",\"param\":\"response_format\"}} | OUTPUT_FORMAT",
        "400 | {\"error\":{\"message\":\"output_config.format: structured outputs are not supported for this model\"}} | OUTPUT_FORMAT",
        "404 | {\"error\":{\"message\":\"No endpoints found that can handle the requested parameters\"}} | OUTPUT_FORMAT",
        "400 | {\"error\":{\"message\":\"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.\"}} | TOKEN_FIELD",
        "400 | {\"error\":{\"message\":\"Unrecognized request argument supplied: max_completion_tokens\"}} | TOKEN_FIELD",
        "400 | {\"error\":{\"message\":\"Unsupported parameter: 'reasoning_effort' is not supported with this model.\",\"param\":\"reasoning_effort\"}} | EFFORT",
        "422 | {\"error\":{\"message\":\"json mode is not available\"}} | OUTPUT_FORMAT"
    })
    void shapeRejectionsAreRecognised(int status, String body, AiShapeRejection rejection) {
        AiCallFailure failure = AiErrorClassifier.classify(status, null, null, AiJson.parse(body).orElse(null));

        assertThat(failure.kind()).isEqualTo(AiFailureKind.INVALID_REQUEST);
        assertThat(failure.rejection()).isEqualTo(rejection);
    }

    @Test
    void genericBadRequestsAreNotShapeRejections() {
        assertThat(rejectionOf(400, "{\"error\":{\"message\":\"prompt is too long\"}}")).isNull();
        assertThat(rejectionOf(401, "{\"error\":{\"message\":\"response_format\"}}")).isNull();
        assertThat(rejectionOf(500, "{\"error\":{\"message\":\"response_format\"}}")).isNull();
    }

    @ParameterizedTest
    @CsvSource({"1004, AUTH", "1008, QUOTA", "1027, FILTERED", "1039, TRUNCATED", "1002, RATE_LIMITED", "1000, PROVIDER_ERROR",
        "1013, PROVIDER_ERROR", "2013, PROVIDER_ERROR"})
    void minimaxStatusCodesMapToKinds(int code, AiFailureKind kind) {
        assertThat(AiErrorClassifier.minimaxStatus(code).kind()).isEqualTo(kind);
    }

    @Test
    void embeddedErrorsUseTheirCodeAsAStatus() {
        assertThat(AiErrorClassifier.embeddedError(AiJson.parse("{\"code\":402,\"message\":\"credits\"}").orElseThrow()).kind())
            .isEqualTo(AiFailureKind.QUOTA);
        assertThat(AiErrorClassifier.embeddedError(AiJson.parse("{\"message\":\"upstream\"}").orElseThrow()).retryable()).isTrue();
    }

    @Nullable
    private static AiShapeRejection rejectionOf(int status, String body) {
        return AiErrorClassifier.classify(status, null, null, AiJson.parse(body).orElse(null)).rejection();
    }
}
