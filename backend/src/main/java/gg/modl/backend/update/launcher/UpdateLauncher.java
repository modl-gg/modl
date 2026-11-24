package gg.modl.backend.update.launcher;

import gg.modl.backend.cli.ModlCli;
import gg.modl.backend.update.Deployment;
import gg.modl.backend.update.DeploymentDetector;
import gg.modl.backend.update.JacksonJsonMaps;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.install.VerifiedArtifacts;
import gg.modl.update.ArtifactKind;
import gg.modl.update.ArtifactSelection;
import gg.modl.update.EmbeddedUpdateContract;
import gg.modl.update.HostDescriptor;
import gg.modl.update.ReleaseKeys;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.Selection;
import gg.modl.update.TransactionKind;
import gg.modl.update.UpdateFiles;
import gg.modl.update.UpdateTransaction;
import gg.modl.update.VerifiedCopy;
import org.jspecify.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public final class UpdateLauncher {
    static final int FAILURE = 1;
    private static final int IN_PROCESS = -1;

    private static volatile String[] arguments = new String[0];
    private static volatile @Nullable LauncherSettings settings;

    private final UpdateFiles files;
    private final ReleaseVersion base;
    private final ReleaseKeys keys;
    private final Path baseJar;
    private final PrintStream out;

    UpdateLauncher(UpdateFiles files, ReleaseVersion base, ReleaseKeys keys, Path baseJar, PrintStream out) {
        this.files = files;
        this.base = base;
        this.keys = keys;
        this.baseJar = baseJar;
        this.out = out;
    }

    public static boolean takeOver(String[] args, Map<String, Object> dotenv) {
        arguments = args.clone();
        LauncherSettings resolved = LauncherSettings.resolve(args, dotenv);
        settings = resolved;
        if (isChild()) {
            LauncherChannel.install(System.in);
            return false;
        }
        File jar = DeploymentDetector.runningJar();
        if (DeploymentDetector.detect(resolved.deployment()) != Deployment.JAR || jar == null) {
            return false;
        }
        UpdateFiles files = new UpdateFiles(resolved.dataDirectory(), JacksonJsonMaps.INSTANCE);
        if (!files.hasState()) {
            return false;
        }
        int code = new UpdateLauncher(files, ReleaseBuild.current().version(), ReleaseKeys.bundled(), jar.toPath(), System.err)
            .supervise(args, true);
        if (code == IN_PROCESS) {
            return false;
        }
        System.exit(code);
        return true;
    }

    public static int superviseAfterInProcessServer() {
        File jar = DeploymentDetector.runningJar();
        if (jar == null) {
            return FAILURE;
        }
        UpdateFiles files = new UpdateFiles(launcherDataDirectory(), JacksonJsonMaps.INSTANCE);
        return new UpdateLauncher(files, ReleaseBuild.current().version(), ReleaseKeys.bundled(), jar.toPath(), System.err)
            .supervise(arguments, false);
    }

    public static Path launcherDataDirectory() {
        LauncherSettings current = settings;
        if (current == null) {
            current = LauncherSettings.resolve(arguments, Map.of());
            settings = current;
        }
        return current.dataDirectory();
    }

    public static boolean isChild() {
        return System.getenv(EmbeddedUpdateContract.LAUNCHER_ENVIRONMENT) != null;
    }

    public static boolean restartsChildren(ReleaseVersion running) {
        return restartsChildren(System.getenv(EmbeddedUpdateContract.LAUNCHER_ENVIRONMENT), running);
    }

    static boolean restartsChildren(@Nullable String launcherDescriptor, ReleaseVersion running) {
        if (launcherDescriptor == null) {
            return true;
        }
        HostDescriptor parent = HostDescriptor.parse(launcherDescriptor);
        return parent != null && !running.isNewerThan(parent.version());
    }

    public static HostDescriptor descriptor(ReleaseVersion base, ReleaseKeys keys) {
        return new HostDescriptor(EmbeddedUpdateContract.LAUNCHER_API, base, keys.keyIds());
    }

    int supervise(String[] args, boolean mayRunInProcess) {
        SupervisedServer server = new SupervisedServer(files, descriptor(base, keys).toString(), out);
        try {
            return ModlCli.handles(args) ? runCommand(args, mayRunInProcess, server) : superviseServer(args, mayRunInProcess, server);
        } catch (IOException e) {
            out.println("modl cannot start: " + e.getMessage());
            return FAILURE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FAILURE;
        }
    }

    private int runCommand(String[] args, boolean mayRunInProcess, SupervisedServer server) throws IOException, InterruptedException {
        Selection selection = ArtifactSelection.forCommand(base, TransactionKind.JAR, false, files.readState());
        if (selection.usesBase() && mayRunInProcess) {
            return IN_PROCESS;
        }
        try (Launch launch = new Launch(selection, commandJar(selection))) {
            return server.run(ServerProcessCommand.forCurrentJvm(launch.jar().path(), args), false).code();
        }
    }

    private ChildJar commandJar(Selection selection) throws IOException {
        String artifact = selection.artifact();
        if (artifact == null) {
            return ChildJar.base(baseJar);
        }
        try {
            return ChildJar.verified(VerifiedArtifacts.copy(files, artifact, ArtifactKind.SERVER, keys));
        } catch (IOException e) {
            throw unusable(selection, e);
        }
    }

    private int superviseServer(String[] args, boolean mayRunInProcess, SupervisedServer server)
        throws IOException, InterruptedException {
        boolean first = true;
        while (true) {
            SupervisedServer.ChildExit exit;
            Selection selection;
            try (Launch launch = nextLaunch()) {
                selection = launch.selection();
                if (selection.target() == Selection.Target.REFUSED) {
                    out.println("modl cannot start: " + selection.refusal());
                    return FAILURE;
                }
                if (selection.usesBase() && first && mayRunInProcess) {
                    return IN_PROCESS;
                }
                first = false;
                exit = server.run(ServerProcessCommand.forCurrentJvm(launch.jar().path(), args), selection.supervisedWithDeadline());
            }
            if (server.stopping()) {
                return exit.code();
            }
            if (exit.restartRequested()) {
                continue;
            }
            if (exit.restartForUpdate()) {
                if (files.readState().pending() == null) {
                    out.println("modl asked to restart for an update, but " + files.pendingFile() + " holds none");
                    return FAILURE;
                }
                continue;
            }
            if (selection.target() != Selection.Target.PENDING) {
                return exit.code();
            }
            UpdateTransaction unfinished = files.readState().pending();
            UpdateTransaction attempted = selection.pendingToWrite();
            if (unfinished == null || attempted == null || !unfinished.id().equals(attempted.id())) {
                return exit.code();
            }
            if (!rollBackUnfinished(unfinished)) {
                return exit.code() == 0 ? FAILURE : exit.code();
            }
        }
    }

    private boolean rollBackUnfinished(UpdateTransaction pending) throws IOException {
        if (!pending.rollbackAllowed()) {
            out.println("The update from modl " + pending.from() + " to " + pending.to() + " did not finish and changed the database"
                + " without a backup. Fix the cause and start modl again, or restore your own backup.");
            return false;
        }
        files.writePending(pending.rollingBack());
        out.println("The update to modl " + pending.to() + " did not start; returning to modl " + pending.from());
        return true;
    }

    private Launch nextLaunch() throws IOException {
        Selection selection = ArtifactSelection.select(base, TransactionKind.JAR, false, files.readState());
        if (selection.deletePending()) {
            files.deletePending();
        }
        if (selection.pendingToWrite() != null) {
            files.writePending(selection.pendingToWrite());
        }
        if (selection.artifact() == null) {
            return new Launch(selection, ChildJar.base(baseJar));
        }
        try {
            return new Launch(selection, ChildJar.verified(VerifiedArtifacts.copy(files, selection.artifact(), ArtifactKind.SERVER, keys)));
        } catch (IOException e) {
            rollBackUnusable(selection, e);
            return nextLaunch();
        }
    }

    private void rollBackUnusable(Selection selection, IOException problem) throws IOException {
        UpdateTransaction pending = files.readState().pending();
        if (selection.target() != Selection.Target.PENDING || pending == null || !pending.rollbackAllowed()) {
            throw unusable(selection, problem);
        }
        out.println("The downloaded modl " + selection.version() + " is unusable (" + problem.getMessage() + "); returning to modl "
            + pending.from());
        files.writePending(pending.rollingBack());
    }

    private IOException unusable(Selection selection, IOException problem) {
        return new IOException("modl " + selection.version() + " in " + files.root() + " is unusable (" + problem.getMessage()
            + "). Replace " + baseJar.getFileName() + " with modl " + selection.version() + " or newer.", problem);
    }

    private record Launch(Selection selection, ChildJar jar) implements AutoCloseable {
        @Override
        public void close() {
            jar.close();
        }
    }

    private record ChildJar(Path path, Optional<VerifiedCopy> copy) implements AutoCloseable {
        static ChildJar base(Path jar) {
            return new ChildJar(jar, Optional.empty());
        }

        static ChildJar verified(VerifiedCopy copy) {
            return new ChildJar(copy.file(), Optional.of(copy));
        }

        @Override
        public void close() {
            copy.ifPresent(VerifiedCopy::close);
        }
    }
}
