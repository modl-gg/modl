package gg.modl.backend.infrastructure.onetimecode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OneTimeCodeCodecTest {

    @Test
    void hashesAreKeyedBySecret() {
        String first = new OneTimeCodeCodec(() -> "one").hash("123456");

        assertThat(first).isEqualTo(new OneTimeCodeCodec(() -> "one").hash("123456"));
        assertThat(first).isNotEqualTo(new OneTimeCodeCodec(() -> "two").hash("123456"));
    }

    @Test
    void aMissingSecretRefusesToHashInsteadOfFallingBackToAnUnkeyedDigest() {
        assertThatThrownBy(() -> new OneTimeCodeCodec(() -> " ").hash("123456")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OneTimeCodeCodec(() -> null).hash("123456")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void numericCodesHaveTheRequestedLength() {
        assertThat(new OneTimeCodeCodec(() -> "s").generateNumericCode(8)).matches("\\d{8}");
    }
}
