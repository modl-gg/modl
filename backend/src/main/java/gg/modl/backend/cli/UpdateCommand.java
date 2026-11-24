package gg.modl.backend.cli;

import gg.modl.backend.database.sql.LockedDatabase;
import gg.modl.backend.update.Deployment;
import gg.modl.backend.update.DeploymentDetector;
import gg.modl.backend.update.JacksonJsonMaps;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.UpdateProperties;
import gg.modl.backend.update.check.DockerCommands;
import gg.modl.backend.update.check.ReleaseLookup;
import gg.modl.backend.update.install.ArtifactDownloader;
import gg.modl.backend.update.launcher.UpdateLauncher;
import gg.modl.update.ArtifactFiles;
import gg.modl.update.ArtifactKind;
import gg.modl.update.EmbeddedUpdateContract;
import gg.modl.update.HostDescriptor;
import gg.modl.update.ReleaseKeys;
import gg.modl.update.ReleaseManifest;
import gg.modl.update.ReleaseVerificationException;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.SignedRelease;
import gg.modl.update.TransactionKind;
import gg.modl.update.UpdateFiles;
import gg.modl.update.UpdateTransaction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;

final class UpdateCommand implements CliCommand {
    private static final String VERSION = "version";
    private static final String VERIFY = "verify";
    private static final String FILES = "files";
    private static final String CHECK = "check";
    private static final String PRERELEASE = "prerelease";
    private static final String ACKNOWLEDGE = "acknowledge-plugin-update";

    @Override
    public String name() {
        return "update";
    }

    @Override
    public String usage() {
        return "update [--check] [--version <v>] [--prerelease] [--acknowledge-plugin-update] | update --verify <modl-release.json>"
            + " --files <dir>";
    }

    @Override
    public Set<String> options() {
        return Set.of(VERSION, VERIFY, FILES);
    }

    @Override
    public Set<String> flags() {
        return Set.of(CHECK, PRERELEASE, ACKNOWLEDGE);
    }

    @Override
    public void run(CliArguments arguments, CliContext context) throws Exception {
        ReleaseKeys keys = ReleaseKeys.bundled();
        if (arguments.optional(VERIFY).isPresent()) {
            verify(Path.of(arguments.required(VERIFY)), Path.of(arguments.required(FILES)), keys, context);
            return;
        }
        if (keys.isEmpty()) {
            throw new CliFailureException("This modl build carries no release keys, so it cannot check or install updates");
        }
        ReleaseBuild build = ReleaseBuild.current();
        UpdateProperties properties = updateProperties(context);
        ReleaseLookup lookup = new ReleaseLookup(properties, build, keys, context.clock());
        Optional<SignedRelease> target = arguments.optional(VERSION).isPresent()
            ? Optional.of(lookup.exact(ReleaseVersion.parse(arguments.required(VERSION))))
            : lookup.newest(build.version(), arguments.flag(PRERELEASE));
        if (target.isEmpty() || !target.get().version().isNewerThan(build.version())) {
            context.out().println("modl " + build.version() + " is up to date");
            return;
        }
        SignedRelease release = target.get();
        if (arguments.flag(CHECK)) {
            context.out().println("modl " + release.version() + " is available (this is modl " + build.version() + "): "
                + properties.releasePage(release.version().toString()));
            return;
        }
        if (DeploymentDetector.detect(context.settings().variable("MODL_DEPLOYMENT").orElse(null)) == Deployment.DOCKER) {
            throw new CliUsageException("This is a Docker installation. " + DockerCommands.howToUpdate(release));
        }
        requireInstallable(release, build, keys, arguments.flag(ACKNOWLEDGE));
        try (LockedDatabase ignored = context.lockConfiguredDatabase()) {
            stage(release, build, properties, keys, context);
        }
    }

    private static void stage(SignedRelease release, ReleaseBuild build, UpdateProperties properties, ReleaseKeys keys,
                              CliContext context) throws IOException {
        UpdateFiles files = context.updateFiles();
        if (files.readState().pending() != null) {
            throw new CliFailureException("An update is already in progress (" + files.pendingFile() + "). Start modl to finish it.");
        }
        ReleaseLookup lookup = new ReleaseLookup(properties, build, keys, context.clock());
        new ArtifactDownloader(lookup.http(), properties).download(files, release, release.manifest().artifact(ArtifactKind.SERVER),
            percent -> {
            });
        HostDescriptor launcher = Optional.ofNullable(HostDescriptor.parse(System.getenv(EmbeddedUpdateContract.LAUNCHER_ENVIRONMENT)))
            .orElse(UpdateLauncher.descriptor(build.version(), keys));
        String fromArtifact = launcher.version().equals(build.version())
            ? null
            : files.relativeArtifact(build.version(), ArtifactKind.SERVER);
        files.writePending(UpdateTransaction.builder(UUID.randomUUID().toString(), TransactionKind.JAR, build.version(),
                release.version(), context.clock().instant())
            .managed(true)
            .fromArtifact(fromArtifact)
            .toArtifact(files.relativeArtifact(release.version(), ArtifactKind.SERVER))
            .build());
        context.out().println("Downloaded modl " + release.version() + ". Start modl as usual to finish the update; it backs up the"
            + " database first and returns to modl " + build.version() + " if the new version does not start.");
    }

    private static void requireInstallable(SignedRelease release, ReleaseBuild build, ReleaseKeys keys, boolean acknowledged) {
        ReleaseManifest manifest = release.manifest();
        if (build.version().isOlderThan(manifest.minUpgradeFrom())) {
            throw new CliFailureException("modl " + release.version() + " upgrades from modl " + manifest.minUpgradeFrom()
                + " or newer. Install modl " + manifest.minUpgradeFrom() + " first.");
        }
        if (Runtime.version().feature() < manifest.minJava()) {
            throw new CliFailureException("modl " + release.version() + " needs Java " + manifest.minJava() + " or newer");
        }
        if (manifest.launcherApi() != EmbeddedUpdateContract.LAUNCHER_API || !keys.knows(release.keyId())) {
            throw new CliFailureException("This modl cannot install modl " + release.version() + " by itself. Replace the jar by hand.");
        }
        if (!release.version().sameLine(build.version()) && !acknowledged) {
            throw new CliFailureException("modl " + release.version() + " needs the modl plugin on every server updated to "
                + release.version().line() + ".x. Run again with --" + ACKNOWLEDGE + " once you have planned that.");
        }
    }

    private static void verify(Path envelope, Path directory, ReleaseKeys keys, CliContext context) throws IOException {
        SignedRelease release;
        try {
            release = SignedRelease.verify(Files.readAllBytes(envelope), keys, JacksonJsonMaps.INSTANCE);
        } catch (ReleaseVerificationException e) {
            throw new CliFailureException(envelope + " does not verify: " + e.getMessage(), e);
        }
        for (ArtifactKind kind : ArtifactKind.values()) {
            ArtifactFiles.requireMatches(directory.resolve(release.manifest().artifact(kind).file()), release.manifest().artifact(kind));
        }
        context.out().println("Verified modl " + release.version() + " signed with key " + release.keyId());
    }

    private static UpdateProperties updateProperties(CliContext context) {
        return new Binder(ConfigurationPropertySources.from(context.settings().variables()))
            .bind("modl.update", UpdateProperties.class)
            .orElseGet(UpdateProperties::defaults);
    }
}
