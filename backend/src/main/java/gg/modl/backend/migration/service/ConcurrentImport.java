package gg.modl.backend.migration.service;

public interface ConcurrentImport {
    ImportKind kind();

    boolean isInProgress();
}
