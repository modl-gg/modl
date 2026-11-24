package gg.modl.backend.update.check;

import gg.modl.backend.update.JacksonJsonMaps;
import gg.modl.update.ArtifactKind;
import gg.modl.update.ReleaseKeys;
import gg.modl.update.ReleaseVersion;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

final class SignedTestRelease {
    private final KeyPair pair;

    SignedTestRelease() throws Exception {
        pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    ReleaseKeys keys() {
        return ReleaseKeys.parse(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    }

    byte[] envelope(String version) throws Exception {
        ReleaseVersion parsed = ReleaseVersion.parse(version);
        Map<String, Object> artifacts = new LinkedHashMap<>();
        for (ArtifactKind kind : ArtifactKind.values()) {
            artifacts.put(kind.manifestKey(), Map.of("file", kind.fileName(parsed), "sha256", "a".repeat(64), "size", 10));
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", 1);
        manifest.put("product", "modl");
        manifest.put("version", version);
        manifest.put("channel", parsed.isPrerelease() ? "prerelease" : "stable");
        manifest.put("commit", "c".repeat(40));
        manifest.put("publishedAt", "2026-12-01T12:00:00Z");
        manifest.put("critical", false);
        manifest.put("minUpgradeFrom", "3.0.0");
        manifest.put("minJava", 21);
        manifest.put("launcherApi", 1);
        manifest.put("embeddedHostApi", 1);
        manifest.put("artifacts", artifacts);
        manifest.put("image", Map.of("repository", "ghcr.io/modl-gg/modl", "digest", "sha256:" + "a".repeat(64)));
        byte[] payload = JacksonJsonMaps.INSTANCE.write(manifest);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(payload);
        return JacksonJsonMaps.INSTANCE.write(Map.of(
            "format", 1,
            "keyId", ReleaseKeys.keyId(pair.getPublic().getEncoded()),
            "payload", Base64.getEncoder().encodeToString(payload),
            "signature", Base64.getEncoder().encodeToString(signer.sign())));
    }
}
