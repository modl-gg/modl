package gg.modl.backend.auth.totp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class Base32Test {

    @ParameterizedTest
    @CsvSource({"f, MY", "fo, MZXQ", "foo, MZXW6", "foob, MZXW6YQ", "fooba, MZXW6YTB", "foobar, MZXW6YTBOI"})
    void rfc4648VectorsRoundTripWithoutPadding(String plain, String encoded) {
        byte[] bytes = plain.getBytes(StandardCharsets.US_ASCII);

        assertThat(Base32.encode(bytes)).isEqualTo(encoded);
        assertThat(Base32.decode(encoded)).isEqualTo(bytes);
        assertThat(Base32.decode(encoded.toLowerCase(Locale.ROOT) + "==")).isEqualTo(bytes);
    }

    @Test
    void aTwentyByteSecretEncodesToThirtyTwoCharacters() {
        assertThat(Base32.encode(new byte[Totp.SECRET_BYTES])).hasSize(32);
    }

    @Test
    void foreignCharactersAreRejected() {
        assertThatThrownBy(() -> Base32.decode("MZ1W")).isInstanceOf(IllegalArgumentException.class);
    }
}
