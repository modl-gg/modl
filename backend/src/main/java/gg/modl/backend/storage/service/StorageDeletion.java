package gg.modl.backend.storage.service;

import java.util.Set;

public record StorageDeletion(boolean existed, Set<String> undeletedObjects) {
    public static final StorageDeletion NOTHING = new StorageDeletion(false, Set.of());

    public StorageDeletion {
        undeletedObjects = Set.copyOf(undeletedObjects);
    }
}
