package gg.modl.backend.ai.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.infrastructure.exception.ValidationException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AiBaseUrlPolicyTest {
    private final AiBaseUrlPolicy policy = new AiBaseUrlPolicy(new AiAddressPolicy());

    @ParameterizedTest
    @CsvSource({
        "HTTPS://API.OpenAI.com/v1/, https://api.openai.com/v1",
        "https://api.openai.com:443/v1, https://api.openai.com/v1",
        "http://localhost:11434/v1, http://localhost:11434/v1",
        "http://127.0.0.1:80/v1//, http://127.0.0.1/v1",
        "http://[::1]:1234/v1, http://[::1]:1234/v1",
        "https://gateway.internal:8443, https://gateway.internal:8443",
        "http://ai-stub:8089/v1, http://ai-stub:8089/v1"
    })
    void acceptedUrlsAreNormalised(String raw, String expected) {
        assertThat(policy.require(raw)).isEqualTo(URI.create(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ftp://example.com", "https://user:pass@example.com/v1", "https://example.com/v1?key=1", "https://example.com/v1#x",
        "https://example.com/v1/../admin", "https://example.com/./v1", "https://example.com/v1%2F..%2Fadmin",
        "https://example.com\\v1", "https://exa mple.com", "https://exämple.com", "https://2130706433/v1",
        "https://0x7f000001/v1", "https://127.1/v1", "https://example.com:0", "https://example.com:70000", "/v1", "",
        "https://example.com/\u0007"
    })
    void malformedUrlsAreInvalid(String raw) {
        assertThat(policy.check(raw).rejection()).isEqualTo(AiBaseUrlPolicy.INVALID);
    }

    @Test
    void anOverlongUrlIsInvalid() {
        assertThat(policy.check("https://example.com/" + "a".repeat(2048)).rejection()).isEqualTo(AiBaseUrlPolicy.INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://169.254.169.254/latest", "https://169.254.169.254", "http://[fe80::1]/v1", "https://0.0.0.0"})
    void metadataAndLinkLocalLiteralsAreBlocked(String raw) {
        assertThat(policy.check(raw).rejection()).isEqualTo(AiBaseUrlPolicy.BLOCKED);
    }

    @Test
    void plainHttpToAPublicLiteralIsRefused() {
        assertThatThrownBy(() -> policy.require("http://8.8.8.8/v1"))
            .isInstanceOf(ValidationException.class)
            .hasFieldOrPropertyWithValue("machineCode", AiBaseUrlPolicy.HTTP_PUBLIC);
    }

    @Test
    void hostNamesAreNotResolvedWhenSaving() {
        assertThat(policy.check("http://name.that.does.not.resolve.invalid/v1").validUrl()).isPresent();
    }
}
