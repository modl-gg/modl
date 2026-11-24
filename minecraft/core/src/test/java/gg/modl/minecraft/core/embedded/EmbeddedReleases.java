package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.update.ArtifactKind;
import gg.modl.update.ReleaseKeys;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.SignedRelease;
import gg.modl.update.TransactionKind;
import gg.modl.update.UpdateFiles;
import gg.modl.update.UpdateTransaction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class EmbeddedReleases {
    static final ReleaseVersion PLUGIN = ReleaseVersion.parse("3.0.0");
    static final ReleaseVersion PATCH = ReleaseVersion.parse("3.0.1");
    static final ReleaseVersion NEXT_PATCH = ReleaseVersion.parse("3.0.2");
    static final ReleaseVersion MINOR = ReleaseVersion.parse("3.1.0");
    static final String TRANSACTION = "transaction-1";
    private static final Instant CREATED = Instant.parse("2026-12-01T12:00:00Z");
    private static final String ALGORITHM = "Ed25519";

    private final Path work;
    private final EmbeddedServerPaths paths;
    private final PluginLogger logger;
    private final KeyPair pair;
    private final UpdateFiles files;
    private final PayloadArchives archives;
    private final BasePayload base;

    EmbeddedReleases(Path work, EmbeddedServerPaths paths, PluginLogger logger) throws IOException, GeneralSecurityException {
        this.work = work;
        this.paths = paths;
        this.logger = logger;
        this.pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        this.files = new UpdateFiles(paths.getDataDirectory(), GsonJsonMaps.INSTANCE);
        this.archives = PayloadZips.archives(paths, MavenCentralDownloader.create(logger), logger);
        payloadDirectory(PLUGIN);
        this.base = PayloadZips.base(work.resolve(PLUGIN.toString()), work.resolve("base-archive"), archives);
    }

    static String patchLibrary(ReleaseVersion version) {
        return "patch-" + version + ".jar";
    }

    static String artifactOf(ReleaseVersion version) {
        return "versions/" + version + "/" + ArtifactKind.EMBEDDED.fileName(version);
    }

    static UpdateTransaction.Builder update(ReleaseVersion from, ReleaseVersion to) {
        return UpdateTransaction.builder(TRANSACTION, TransactionKind.EMBEDDED, from, to, CREATED)
                .managed(true)
                .fromArtifact(from.equals(PLUGIN) ? null : artifactOf(from))
                .toArtifact(artifactOf(to));
    }

    UpdateFiles files() {
        return files;
    }

    BasePayload base() {
        return base;
    }

    PayloadArchives archives() {
        return archives;
    }

    Set<String> baseLibraries() throws IOException {
        try (VerifiedArchive archive = base.open(() -> false)) {
            return archive.payload().libraryFileNames();
        }
    }

    ReleaseKeys keys() {
        return ReleaseKeys.parse(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    }

    ManagedPayloads payloads() {
        return ManagedPayloads.managed(base, archives, PLUGIN, paths.getDataDirectory(), keys(), logger);
    }

    Path publish(ReleaseVersion version) throws IOException, GeneralSecurityException {
        return publish(version, version);
    }

    Path publish(ReleaseVersion version, ReleaseVersion contents) throws IOException, GeneralSecurityException {
        payloadDirectory(contents);
        Path zip = PayloadZips.zip(work.resolve(contents.toString()),
                files.versionDirectory(version).resolve(ArtifactKind.EMBEDDED.fileName(version)));
        Files.write(files.envelope(version), envelope(manifest(version, zip)));
        return zip;
    }

    private void payloadDirectory(ReleaseVersion version) throws IOException {
        Path directory = work.resolve(version.toString());
        FakeServerPayload.builder(directory)
                .packed(patchLibrary(version), version.toString().getBytes(StandardCharsets.UTF_8))
                .write();
        Files.write(directory.resolve(EmbeddedPayload.VERSION_FILE), (version + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> manifest(ReleaseVersion version, Path zip) throws IOException {
        Map<String, Object> artifacts = new LinkedHashMap<>();
        for (ArtifactKind kind : ArtifactKind.values()) {
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("file", kind.fileName(version));
            artifact.put("sha256", PayloadBuilder.sha256(Files.readAllBytes(zip)));
            artifact.put("size", Files.size(zip));
            artifacts.put(kind.manifestKey(), artifact);
        }
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("repository", "ghcr.io/modl-gg/modl");
        image.put("digest", "sha256:" + repeated('b', 64));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", 1);
        manifest.put("product", "modl");
        manifest.put("version", version.toString());
        manifest.put("channel", "stable");
        manifest.put("commit", repeated('c', 40));
        manifest.put("publishedAt", CREATED.toString());
        manifest.put("critical", false);
        manifest.put("minUpgradeFrom", PLUGIN.toString());
        manifest.put("minJava", 21);
        manifest.put("launcherApi", 1);
        manifest.put("embeddedHostApi", 1);
        manifest.put("artifacts", artifacts);
        manifest.put("image", image);
        return manifest;
    }

    private byte[] envelope(Map<String, Object> manifest) throws GeneralSecurityException {
        byte[] payload = GsonJsonMaps.INSTANCE.write(manifest);
        Signature signer = Signature.getInstance(ALGORITHM);
        signer.initSign(pair.getPrivate());
        signer.update(payload);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("format", SignedRelease.ENVELOPE_FORMAT);
        envelope.put("keyId", ReleaseKeys.keyId(pair.getPublic().getEncoded()));
        envelope.put("payload", Base64.getEncoder().encodeToString(payload));
        envelope.put("signature", Base64.getEncoder().encodeToString(signer.sign()));
        return GsonJsonMaps.INSTANCE.write(envelope);
    }

    private static String repeated(char character, int length) {
        char[] characters = new char[length];
        Arrays.fill(characters, character);
        return new String(characters);
    }
}
