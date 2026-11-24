package gg.modl.backend.storage.backend;

import gg.modl.backend.infrastructure.util.FailureDescriptions;
import gg.modl.backend.storage.ListedObject;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.OldCopyRemovals;
import gg.modl.backend.storage.OwnershipMarker;
import gg.modl.backend.storage.StorageNamespace;
import gg.modl.backend.storage.service.StorageOwnerMarker;
import gg.modl.backend.storage.service.StorageOwnerMarker.Verdict;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Slf4j
@Component
class OldCopyRemoval implements OldCopyRemovals {
    static final int DELETE_BATCH = 1000;

    private final StorageOwnerMarker ownerMarker;
    private final AtomicBoolean running = new AtomicBoolean();

    OldCopyRemoval(StorageOwnerMarker ownerMarker) {
        this.ownerMarker = ownerMarker;
    }

    static boolean holdsFiles(ObjectStorage storage) {
        try (Stream<ListedObject> objects = storage.list(StorageNamespace.KEY_PREFIX)) {
            return objects.anyMatch(object -> !OwnershipMarker.isMarkerKey(object.key()));
        } catch (RuntimeException e) {
            log.warn("Could not list the files of a former storage", e);
            return true;
        }
    }

    @Override
    public boolean isRemovingOldCopies() {
        return running.get();
    }

    boolean start() {
        return running.compareAndSet(false, true);
    }

    void finish() {
        running.set(false);
    }

    Outcome remove(ObjectStorage oldCopy) {
        Verdict owner = ownerMarker.inspect(oldCopy);
        if (!owner.isOwned() && !owner.isUnclaimed()) {
            return Outcome.refused(owner.requireRefusalReason());
        }
        long removed = 0;
        Set<String> attempted = new HashSet<>();
        Set<String> failed = new HashSet<>();
        try {
            List<String> batch = nextBatch(oldCopy, attempted);
            while (!batch.isEmpty()) {
                attempted.addAll(batch);
                Set<String> notDeleted = oldCopy.deleteAll(batch);
                failed.addAll(notDeleted);
                removed += batch.size() - notDeleted.size();
                batch = nextBatch(oldCopy, attempted);
            }
        } catch (RuntimeException e) {
            log.warn("Removing the old copies stopped", e);
            return Outcome.refused(FailureDescriptions.describe(e));
        }
        return failed.isEmpty() ? Outcome.done(removed) : Outcome.refused(failed.size() + " file(s) could not be deleted");
    }

    private static List<String> nextBatch(ObjectStorage oldCopy, Set<String> skipped) {
        try (Stream<ListedObject> objects = oldCopy.list(StorageNamespace.KEY_PREFIX)) {
            return objects.map(ListedObject::key)
                .filter(key -> !OwnershipMarker.isMarkerKey(key) && !skipped.contains(key))
                .limit(DELETE_BATCH)
                .toList();
        }
    }

    record Outcome(boolean complete, long removed, @Nullable String problem) {
        static Outcome done(long removed) {
            return new Outcome(true, removed, null);
        }

        static Outcome refused(String problem) {
            return new Outcome(false, 0, problem);
        }
    }
}
