package gg.modl.backend.storage.backend;

import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StorageType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

sealed interface StorageRoute {
    StorageType writeKind();

    ObjectStorage writeTarget();

    List<ObjectStorage> readOrder();

    List<ObjectStorage> retiredCopies();

    StorageRoute withRetiredCopies(List<ObjectStorage> retired);

    default List<ObjectStorage> deletionOrder() {
        Set<ObjectStorage> holders = new LinkedHashSet<>(retiredCopies());
        List<ObjectStorage> reversedReads = new ArrayList<>(readOrder());
        holders.addAll(reversedReads.reversed());
        holders.remove(writeTarget());
        holders.add(writeTarget());
        return List.copyOf(holders);
    }

    default List<ObjectStorage> inUse() {
        Set<ObjectStorage> used = new LinkedHashSet<>(readOrder());
        used.addAll(retiredCopies());
        return List.copyOf(used);
    }

    record Stable(StorageType kind, ObjectStorage storage, List<ObjectStorage> fallbacks, List<ObjectStorage> retiredCopies)
        implements StorageRoute {
        public Stable {
            fallbacks = List.copyOf(fallbacks);
            retiredCopies = List.copyOf(retiredCopies);
        }

        Stable(StorageType kind, ObjectStorage storage) {
            this(kind, storage, List.of(), List.of());
        }

        @Override
        public StorageType writeKind() {
            return kind;
        }

        @Override
        public ObjectStorage writeTarget() {
            return storage;
        }

        @Override
        public List<ObjectStorage> readOrder() {
            List<ObjectStorage> order = new ArrayList<>();
            order.add(storage);
            order.addAll(fallbacks);
            return List.copyOf(order);
        }

        @Override
        public Stable withRetiredCopies(List<ObjectStorage> retired) {
            return new Stable(kind, storage, fallbacks, retired);
        }

        Stable withFallbacks(List<ObjectStorage> kept) {
            return new Stable(kind, storage, kept, retiredCopies);
        }
    }

    record Migrating(StorageType sourceKind, ObjectStorage source, StorageType targetKind, ObjectStorage target,
                     List<ObjectStorage> retiredCopies) implements StorageRoute {
        public Migrating {
            retiredCopies = List.copyOf(retiredCopies);
        }

        Migrating(StorageType sourceKind, ObjectStorage source, StorageType targetKind, ObjectStorage target) {
            this(sourceKind, source, targetKind, target, List.of());
        }

        @Override
        public StorageType writeKind() {
            return targetKind;
        }

        @Override
        public ObjectStorage writeTarget() {
            return target;
        }

        @Override
        public List<ObjectStorage> readOrder() {
            return List.of(target, source);
        }

        @Override
        public Migrating withRetiredCopies(List<ObjectStorage> retired) {
            return new Migrating(sourceKind, source, targetKind, target, retired);
        }
    }
}
