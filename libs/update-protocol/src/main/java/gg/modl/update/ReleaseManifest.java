package gg.modl.update;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class ReleaseManifest {
    public static final int FORMAT = 1;
    public static final String PRODUCT = "modl";
    public static final String STABLE_CHANNEL = "stable";
    public static final String PRERELEASE_CHANNEL = "prerelease";

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern IMAGE_REPOSITORY = Pattern.compile("[a-z0-9][a-z0-9._/-]{0,199}");
    private static final Pattern IMAGE_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final int MIN_JAVA = 8;
    private static final int MAX_JAVA = 1000;

    private final ReleaseVersion version;
    private final String commit;
    private final Instant publishedAt;
    private final boolean critical;
    private final ReleaseVersion minUpgradeFrom;
    private final int minJava;
    private final int launcherApi;
    private final int embeddedHostApi;
    private final Map<ArtifactKind, ReleaseArtifact> artifacts;
    private final String imageRepository;
    private final String imageDigest;

    private ReleaseManifest(ReleaseVersion version, String commit, Instant publishedAt, boolean critical, ReleaseVersion minUpgradeFrom,
                            ReleaseRuntime runtime, Map<ArtifactKind, ReleaseArtifact> artifacts, String imageRepository,
                            String imageDigest) {
        this.version = version;
        this.commit = commit;
        this.publishedAt = publishedAt;
        this.critical = critical;
        this.minUpgradeFrom = minUpgradeFrom;
        this.minJava = runtime.minJava;
        this.launcherApi = runtime.launcherApi;
        this.embeddedHostApi = runtime.embeddedHostApi;
        this.artifacts = Collections.unmodifiableMap(artifacts);
        this.imageRepository = imageRepository;
        this.imageDigest = imageDigest;
    }

    static ReleaseManifest parse(byte[] payload, JsonMaps json) throws ReleaseVerificationException {
        Map<String, Object> values;
        try {
            values = json.read(payload);
        } catch (IOException | RuntimeException e) {
            throw new ReleaseVerificationException(ReleaseVerificationException.Reason.INVALID_MANIFEST,
                "The release manifest is not valid JSON", e);
        }
        JsonFields fields = new JsonFields(values, "The release manifest");
        try {
            requireFormat(fields.integer("format"));
            return read(fields);
        } catch (InvalidJsonFieldException e) {
            throw new ReleaseVerificationException(ReleaseVerificationException.Reason.INVALID_MANIFEST, e.getMessage(), e);
        }
    }

    private static void requireFormat(int format) throws ReleaseVerificationException {
        if (format > FORMAT) {
            throw new ReleaseVerificationException(ReleaseVerificationException.Reason.UNSUPPORTED_FORMAT,
                "The release manifest has format " + format + ", which this modl does not read; update modl manually");
        }
        if (format != FORMAT) {
            throw new ReleaseVerificationException(ReleaseVerificationException.Reason.INVALID_MANIFEST,
                "The release manifest has an invalid format " + format);
        }
    }

    private static ReleaseManifest read(JsonFields fields) throws InvalidJsonFieldException {
        if (!PRODUCT.equals(fields.text("product"))) {
            throw new InvalidJsonFieldException("The release manifest is not for " + PRODUCT);
        }
        ReleaseVersion version = fields.version("version");
        String expectedChannel = version.isPrerelease() ? PRERELEASE_CHANNEL : STABLE_CHANNEL;
        if (!expectedChannel.equals(fields.text("channel"))) {
            throw new InvalidJsonFieldException("The release manifest channel does not match version " + version);
        }
        String commit = fields.text("commit");
        if (!COMMIT.matcher(commit).matches()) {
            throw new InvalidJsonFieldException("The release manifest has an invalid commit");
        }
        Instant publishedAt = fields.instant("publishedAt");
        boolean critical = fields.flag("critical");
        ReleaseVersion minUpgradeFrom = fields.version("minUpgradeFrom");
        if (minUpgradeFrom.isNewerThan(version)) {
            throw new InvalidJsonFieldException("The release manifest minUpgradeFrom is newer than its version");
        }
        int minJava = fields.integer("minJava");
        if (minJava < MIN_JAVA || minJava > MAX_JAVA) {
            throw new InvalidJsonFieldException("The release manifest has an invalid minJava");
        }
        ReleaseRuntime runtime = new ReleaseRuntime(minJava, positive(fields, "launcherApi"), positive(fields, "embeddedHostApi"));
        JsonFields artifactFields = fields.object("artifacts");
        Map<ArtifactKind, ReleaseArtifact> artifacts = new EnumMap<ArtifactKind, ReleaseArtifact>(ArtifactKind.class);
        for (ArtifactKind kind : ArtifactKind.values()) {
            artifacts.put(kind, ReleaseArtifact.parse(kind, version, artifactFields.object(kind.manifestKey())));
        }
        JsonFields image = fields.object("image");
        String imageRepository = image.text("repository");
        String imageDigest = image.text("digest");
        if (!IMAGE_REPOSITORY.matcher(imageRepository).matches() || !IMAGE_DIGEST.matcher(imageDigest).matches()) {
            throw new InvalidJsonFieldException("The release manifest has an invalid image");
        }
        return new ReleaseManifest(version, commit, publishedAt, critical, minUpgradeFrom, runtime, artifacts, imageRepository, imageDigest);
    }

    private static int positive(JsonFields fields, String name) throws InvalidJsonFieldException {
        int value = fields.integer(name);
        if (value < 1) {
            throw new InvalidJsonFieldException("The release manifest has an invalid " + name);
        }
        return value;
    }

    public ReleaseVersion version() {
        return version;
    }

    public String commit() {
        return commit;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public boolean critical() {
        return critical;
    }

    public ReleaseVersion minUpgradeFrom() {
        return minUpgradeFrom;
    }

    public int minJava() {
        return minJava;
    }

    public int launcherApi() {
        return launcherApi;
    }

    public int embeddedHostApi() {
        return embeddedHostApi;
    }

    public ReleaseArtifact artifact(ArtifactKind kind) {
        ReleaseArtifact artifact = artifacts.get(kind);
        if (artifact == null) {
            throw new IllegalStateException("A parsed release manifest lists every artifact kind, but not " + kind);
        }
        return artifact;
    }

    public String imageRepository() {
        return imageRepository;
    }

    public String imageDigest() {
        return imageDigest;
    }

    private static final class ReleaseRuntime {
        private final int minJava;
        private final int launcherApi;
        private final int embeddedHostApi;

        private ReleaseRuntime(int minJava, int launcherApi, int embeddedHostApi) {
            this.minJava = minJava;
            this.launcherApi = launcherApi;
            this.embeddedHostApi = embeddedHostApi;
        }
    }
}
