package gg.modl.backend.infrastructure.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KeyedHmacTest {

    @Test
    void signaturesMatchTheRfc4231Vector() {
        KeyedHmac hmac = new KeyedHmac(() -> "Jefe");

        assertThat(hmac.signHex("what do ya want for nothing?"))
            .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void verificationIsKeyAndMessageBound() {
        KeyedHmac hmac = new KeyedHmac(() -> "key-a");
        byte[] signature = hmac.sign("message");

        assertThat(hmac.verify("message", signature)).isTrue();
        assertThat(hmac.verify("other", signature)).isFalse();
        assertThat(new KeyedHmac(() -> "key-b").verify("message", signature)).isFalse();
        assertThat(hmac.verify("message", null)).isFalse();
    }

    @Test
    void aBlankKeyFailsFast() {
        assertThatThrownBy(() -> new KeyedHmac(() -> " ").sign("message")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KeyedHmac(() -> null).sign("message")).isInstanceOf(IllegalStateException.class);
    }
}
