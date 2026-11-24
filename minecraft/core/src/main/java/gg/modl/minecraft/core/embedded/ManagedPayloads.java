package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.update.ArtifactKind;
import gg.modl.update.ArtifactSelection;
import gg.modl.update.CommittedUpdate;
import gg.modl.update.EmbeddedUpdateContract;
import gg.modl.update.ReleaseKeys;
import gg.modl.update.ReleaseManifest;
import gg.modl.update.ReleaseVerificationException;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.Selection;
import gg.modl.update.SignedRelease;
import gg.modl.update.TransactionKind;
import gg.modl.update.UpdateFiles;
import gg.modl.update.UpdatePhase;
import gg.modl.update.UpdateState;
import gg.modl.update.UpdateTransaction;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

public final class ManagedPayloads {
    static final int MAX_FAILURE_LENGTH = 1000;

    private final BasePayload basePayload;
    private final @Nullable UpdateStore store;
    private volatile @Nullable Set<String> runningLibraries;
    private volatile @Nullable String attemptId;

    private ManagedPayloads(BasePayload basePayload, @Nullable UpdateStore store) {
        this.basePayload = basePayload;
        this.store = store;
    }

    public static ManagedPayloads unmanaged(BasePayload basePayload) {
        return new ManagedPayloads(basePayload, null);
    }

    public static ManagedPayloads managed(BasePayload basePayload, PayloadArchives archives, ReleaseVersion base,
                                          Path dataDirectory, ReleaseKeys keys, PluginLogger logger) {
        return new ManagedPayloads(basePayload, new UpdateStore(archives, base,
                new UpdateFiles(dataDirectory, GsonJsonMaps.INSTANCE), keys, logger));
    }

    boolean managesUpdates() {
        return store != null;
    }

    SelectedPayload select(BooleanSupplier cancelled) throws IOException {
        UpdateStore managed = store;
        if (managed == null) return SelectedPayload.of(basePayload.open(cancelled), null, Selection.Target.BASE);
        attemptId = null;
        UpdateState state = managed.files.readState();
        Selection selection = ArtifactSelection.select(managed.base, TransactionKind.EMBEDDED, true, state);
        if (selection.deletePending()) managed.files.deletePending();
        UpdateTransaction pending = state.pending();
        switch (selection.target()) {
            case REFUSED:
                throw new EmbeddedServerException(selection.refusal());
            case COMMITTED:
                return committed(managed, selection);
            case PENDING:
                if (pending == null) throw new IllegalStateException("an update attempt was selected without a pending update");
                return updateAttempt(managed, selection, pending, cancelled);
            case ROLLBACK:
                return rollback(managed, selection, cancelled);
            default:
                return SelectedPayload.of(basePayload.open(cancelled), managed.base, Selection.Target.BASE);
        }
    }

    Optional<SelectedPayload> rollBack(SelectedPayload failed, @Nullable String reason, BooleanSupplier cancelled)
            throws IOException {
        UpdateStore managed = store;
        if (managed == null) return Optional.empty();
        UpdateState state = managed.files.readState();
        UpdateTransaction pending = state.pending();
        if (pending == null || !pending.id().equals(failed.transactionId()) || !pending.rollbackAllowed()) {
            return Optional.empty();
        }
        managed.files.writePending(pending.rollingBack().toBuilder().failure(bounded(reason)).build());
        return Optional.of(select(cancelled));
    }

    boolean committed(SelectedPayload selected) throws IOException {
        UpdateStore managed = store;
        if (managed == null) return false;
        CommittedUpdate committed = managed.files.readState().committed();
        return committed != null && committed.transactionId().equals(selected.transactionId());
    }

    boolean hasStagedUpdate() throws IOException {
        UpdateStore managed = store;
        if (managed == null) return false;
        UpdateTransaction pending = managed.files.readState().pending();
        return pending != null && pending.managed() && pending.kind() == TransactionKind.EMBEDDED
                && pending.phase() == UpdatePhase.STAGED;
    }

    VerifiedArchive verifiedArchive(ReleaseVersion version) throws IOException {
        return verifiedArchive(requireStore(), version);
    }

    void started(InstalledPayload installed) {
        runningLibraries = installed.libraryFileNames();
    }

    Set<String> runningLibraries() throws IOException {
        Set<String> current = runningLibraries;
        if (current == null) throw new IOException("the built-in panel is not running");
        return current;
    }

    public void abandonStalledAttempt(String reason) {
        String attempt = attemptId;
        UpdateStore managed = store;
        if (attempt == null || managed == null) return;
        try {
            UpdateTransaction pending = managed.files.readState().pending();
            if (pending != null && pending.id().equals(attempt) && pending.phase() != UpdatePhase.ROLLBACK) {
                managed.files.writePending(pending.timedOut(bounded(reason)));
            }
        } catch (IOException unwritable) {
            managed.logger.warning("The stalled update could not be recorded (" + unwritable.getMessage()
                    + "). Restart the Minecraft server to finish or roll back the update.");
        }
    }

    private UpdateStore requireStore() throws IOException {
        UpdateStore managed = store;
        if (managed == null) throw new IOException("this modl build does not install updates");
        return managed;
    }

    private SelectedPayload committed(UpdateStore managed, Selection selection) throws IOException {
        try {
            return SelectedPayload.of(verifiedArchive(managed, selection.version()), selection.version(),
                    Selection.Target.COMMITTED);
        } catch (IOException invalid) {
            throw new EmbeddedServerException("The installed update to modl " + selection.version() + " cannot be used ("
                    + invalid.getMessage() + "). Replace the modl plugin jar with modl " + selection.version() + " or newer.",
                    invalid);
        }
    }

    private SelectedPayload updateAttempt(UpdateStore managed, Selection selection, UpdateTransaction pending,
                                          BooleanSupplier cancelled) throws IOException {
        VerifiedArchive archive;
        try {
            archive = verifiedArchive(managed, selection.version());
        } catch (IOException invalid) {
            if (pending.phase() != UpdatePhase.STAGED) throw invalid;
            managed.logger.warning("The staged update to modl " + selection.version() + " cannot be used ("
                    + invalid.getMessage() + "). Staying on modl " + pending.from() + ".");
            managed.files.writePending(pending.rollingBack().toBuilder().failure(bounded(invalid.getMessage())).build());
            return select(cancelled);
        }
        try {
            writePendingOf(managed, selection);
            SelectedPayload attempt = SelectedPayload.updateAttempt(archive, selection.version(), pending.id(),
                    previousLibraries(managed, pending));
            attemptId = pending.id();
            return attempt;
        } catch (IOException | RuntimeException failure) {
            archive.close();
            throw failure;
        }
    }

    private SelectedPayload rollback(UpdateStore managed, Selection selection, BooleanSupplier cancelled)
            throws IOException {
        writePendingOf(managed, selection);
        if (selection.usesBase()) {
            return SelectedPayload.of(basePayload.open(cancelled), managed.base, Selection.Target.ROLLBACK);
        }
        try {
            return SelectedPayload.of(verifiedArchive(managed, selection.version()), selection.version(),
                    Selection.Target.ROLLBACK);
        } catch (IOException invalid) {
            throw new EmbeddedServerException("modl " + selection.version() + " is needed to undo the failed update, but its"
                    + " files cannot be used (" + invalid.getMessage() + "). Replace the modl plugin jar with modl "
                    + selection.version() + " to restore the database.", invalid);
        }
    }

    private static void writePendingOf(UpdateStore managed, Selection selection) throws IOException {
        UpdateTransaction toWrite = selection.pendingToWrite();
        if (toWrite != null) managed.files.writePending(toWrite);
    }

    private Set<String> previousLibraries(UpdateStore managed, UpdateTransaction pending) {
        Set<String> running = runningLibraries;
        if (running != null) return running;
        try {
            return libraryFileNamesOf(managed, pending);
        } catch (IOException unreadable) {
            managed.logger.debug("Could not read the libraries of modl " + pending.from() + ": " + unreadable.getMessage());
            return Collections.emptySet();
        }
    }

    private Set<String> libraryFileNamesOf(UpdateStore managed, UpdateTransaction pending) throws IOException {
        if (pending.fromArtifact() != null) return libraryFileNamesOf(verifiedArchive(managed, pending.from()));
        if (managed.base.equals(pending.from())) return libraryFileNamesOf(basePayload.openInstalled());
        return Collections.emptySet();
    }

    private static Set<String> libraryFileNamesOf(VerifiedArchive opened) throws IOException {
        try (VerifiedArchive archive = opened) {
            return archive.payload().libraryFileNames();
        }
    }

    private static VerifiedArchive verifiedArchive(UpdateStore managed, ReleaseVersion version) throws IOException {
        SignedRelease release = verifiedRelease(managed, version);
        Path zip = managed.files.versionDirectory(version).resolve(ArtifactKind.EMBEDDED.fileName(version));
        return managed.archives.open(zip, release.manifest().artifact(ArtifactKind.EMBEDDED), version);
    }

    private static SignedRelease verifiedRelease(UpdateStore managed, ReleaseVersion version) throws IOException {
        Path envelope = managed.files.envelope(version);
        if (!Files.isRegularFile(envelope) || Files.size(envelope) > SignedRelease.MAX_ENVELOPE_BYTES) {
            throw new IOException(envelope + " is missing or too large");
        }
        SignedRelease release;
        try {
            release = SignedRelease.verify(Files.readAllBytes(envelope), managed.keys, GsonJsonMaps.INSTANCE);
        } catch (ReleaseVerificationException invalid) {
            throw new IOException("the release file of modl " + version + " does not verify: " + invalid.getMessage(), invalid);
        }
        ReleaseManifest manifest = release.manifest();
        if (!manifest.version().equals(version)) {
            throw new IOException("the release file in " + managed.files.versionDirectory(version) + " is for modl "
                    + manifest.version());
        }
        if (manifest.embeddedHostApi() != EmbeddedUpdateContract.HOST_API) {
            throw new IOException("modl " + version + " needs a newer modl plugin jar");
        }
        return release;
    }

    private static String bounded(@Nullable String reason) {
        String text = reason == null ? "unknown" : reason;
        return text.length() <= MAX_FAILURE_LENGTH ? text : text.substring(0, MAX_FAILURE_LENGTH);
    }

    private static final class UpdateStore {
        private final PayloadArchives archives;
        private final ReleaseVersion base;
        private final UpdateFiles files;
        private final ReleaseKeys keys;
        private final PluginLogger logger;

        private UpdateStore(PayloadArchives archives, ReleaseVersion base, UpdateFiles files, ReleaseKeys keys,
                            PluginLogger logger) {
            this.archives = archives;
            this.base = base;
            this.files = files;
            this.keys = keys;
            this.logger = logger;
        }
    }
}
