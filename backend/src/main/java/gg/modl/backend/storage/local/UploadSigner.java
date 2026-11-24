package gg.modl.backend.storage.local;

import gg.modl.backend.infrastructure.util.KeyedHmac;
import java.util.Base64;
import java.util.function.Supplier;

public final class UploadSigner {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final KeyedHmac hmac;

    public UploadSigner(Supplier<String> signingKey) {
        this.hmac = new KeyedHmac(signingKey);
    }

    public String sign(UploadGrant grant) {
        return ENCODER.encodeToString(hmac.sign(grant.canonicalForm()));
    }

    public boolean verify(UploadGrant grant, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        try {
            return hmac.verify(grant.canonicalForm(), DECODER.decode(signature));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
