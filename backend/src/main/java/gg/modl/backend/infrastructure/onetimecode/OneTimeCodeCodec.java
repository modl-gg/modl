package gg.modl.backend.infrastructure.onetimecode;

import gg.modl.backend.infrastructure.util.KeyedHmac;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.Supplier;

public final class OneTimeCodeCodec {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final KeyedHmac hmac;

    public OneTimeCodeCodec(Supplier<String> secret) {
        this.hmac = new KeyedHmac(secret);
    }

    public String generateNumericCode(int length) {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(SECURE_RANDOM.nextInt(10));
        }
        return code.toString();
    }

    public String hash(String code) {
        return Base64.getEncoder().encodeToString(hmac.sign(code));
    }
}
