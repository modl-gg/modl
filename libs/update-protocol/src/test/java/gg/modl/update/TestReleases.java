package gg.modl.update;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

final class TestReleases {
    private static final String HASH = "b".repeat(64);
    private static final GsonJson JSON = new GsonJson();

    private final KeyPair pair;

    TestReleases() throws GeneralSecurityException {
        this.pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    ReleaseKeys keys() {
        return ReleaseKeys.parse(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    }

    String keyId() {
        return ReleaseKeys.keyId(pair.getPublic().getEncoded());
    }

    static Map<String, Object> manifest(String version) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", 1);
        manifest.put("product", "modl");
        manifest.put("version", version);
        manifest.put("channel", ReleaseVersion.parse(version).isPrerelease() ? "prerelease" : "stable");
        manifest.put("commit", "c".repeat(40));
        manifest.put("publishedAt", "2026-12-01T12:00:00Z");
        manifest.put("critical", false);
        manifest.put("minUpgradeFrom", "3.0.0");
        manifest.put("minJava", 21);
        manifest.put("launcherApi", 1);
        manifest.put("embeddedHostApi", 1);
        Map<String, Object> artifacts = new LinkedHashMap<>();
        for (ArtifactKind kind : ArtifactKind.values()) {
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("file", kind.fileName(ReleaseVersion.parse(version)));
            artifact.put("sha256", HASH);
            artifact.put("size", 10);
            artifacts.put(kind.manifestKey(), artifact);
        }
        manifest.put("artifacts", artifacts);
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("repository", "ghcr.io/modl-gg/modl");
        image.put("digest", "sha256:" + HASH);
        manifest.put("image", image);
        return manifest;
    }

    byte[] envelope(Map<String, Object> manifest) throws GeneralSecurityException {
        byte[] payload = JSON.write(manifest);
        return envelope(payload, sign(payload), keyId(), 1);
    }

    byte[] sign(byte[] payload) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(payload);
        return signer.sign();
    }

    static byte[] envelope(byte[] payload, byte[] signature, String keyId, int format) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("format", format);
        envelope.put("keyId", keyId);
        envelope.put("payload", Base64.getEncoder().encodeToString(payload));
        envelope.put("signature", Base64.getEncoder().encodeToString(signature));
        return JSON.write(envelope);
    }

    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
