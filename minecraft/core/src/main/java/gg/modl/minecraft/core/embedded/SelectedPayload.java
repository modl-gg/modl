package gg.modl.minecraft.core.embedded;

import gg.modl.update.ReleaseVersion;
import gg.modl.update.Selection;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Set;

final class SelectedPayload implements AutoCloseable {
    private final VerifiedArchive archive;
    private final @Nullable ReleaseVersion version;
    private final Selection.Target target;
    private final @Nullable String transactionId;
    private final Set<String> retainedLibraries;

    private SelectedPayload(VerifiedArchive archive, @Nullable ReleaseVersion version, Selection.Target target,
                            @Nullable String transactionId, Set<String> retainedLibraries) {
        this.archive = archive;
        this.version = version;
        this.target = target;
        this.transactionId = transactionId;
        this.retainedLibraries = Collections.unmodifiableSet(retainedLibraries);
    }

    static SelectedPayload of(VerifiedArchive archive, @Nullable ReleaseVersion version, Selection.Target target) {
        return new SelectedPayload(archive, version, target, null, Collections.<String>emptySet());
    }

    static SelectedPayload updateAttempt(VerifiedArchive archive, ReleaseVersion version, String transactionId,
                                         Set<String> retainedLibraries) {
        return new SelectedPayload(archive, version, Selection.Target.PENDING, transactionId, retainedLibraries);
    }

    EmbeddedPayload payload() {
        return archive.payload();
    }

    @Nullable ReleaseVersion version() {
        return version;
    }

    Selection.Target target() {
        return target;
    }

    @Nullable String transactionId() {
        return transactionId;
    }

    Set<String> retainedLibraries() {
        return retainedLibraries;
    }

    boolean attemptsUpdate() {
        return target == Selection.Target.PENDING;
    }

    String describe() {
        return version == null ? "the modl server" : "modl " + version;
    }

    @Override
    public void close() {
        archive.close();
    }
}
