package gg.modl.backend.auth.totp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TotpKeyUriTest {

    @Test
    void theUriNamesTheIssuerAndAccountWithPercentEncoding() {
        String uri = TotpKeyUri.of("My Server: Survival", "mod@example.com", "JBSWY3DPEHPK3PXP");

        assertThat(uri).isEqualTo("otpauth://totp/My%20Server%20Survival:mod%40example.com"
            + "?secret=JBSWY3DPEHPK3PXP&issuer=My%20Server%20Survival&algorithm=SHA1&digits=6&period=30");
        assertThat(uri).doesNotContain("+");
    }
}
