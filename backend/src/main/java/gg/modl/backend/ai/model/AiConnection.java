package gg.modl.backend.ai.model;

import org.jspecify.annotations.Nullable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

public record AiConnection(
    AiProviderDefinition provider,
    URI baseUrl,
    @Nullable String apiKey,
    @Nullable String model,
    @Nullable AiRequestShape shape
) {
    private static final char SEPARATOR = '\u0000';

    public Optional<String> key() {
        return Optional.ofNullable(apiKey);
    }

    public Optional<String> chosenModel() {
        return Optional.ofNullable(model);
    }

    public AiConnection withShape(@Nullable AiRequestShape requestShape) {
        return new AiConnection(provider, baseUrl, apiKey, model, requestShape);
    }

    public String fingerprint() {
        return digest(provider.id(), baseUrl.toString(), model == null ? "" : model, apiKey == null ? "" : apiKey);
    }

    public String credentialFingerprint() {
        return digest(provider.id(), baseUrl.toString(), apiKey == null ? "" : apiKey);
    }

    @Override
    public String toString() {
        return "AiConnection[provider=" + provider.id() + ", baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null ? "none" : "set")
            + ", model=" + model + ", shape=" + shape + "]";
    }

    private static String digest(String... parts) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                sha256.update(part.getBytes(StandardCharsets.UTF_8));
                sha256.update((byte) SEPARATOR);
            }
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
