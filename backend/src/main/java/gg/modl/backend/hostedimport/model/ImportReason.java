package gg.modl.backend.hostedimport.model;

import org.jspecify.annotations.Nullable;
import java.util.Optional;

public enum ImportReason {
    LINK_EXPIRED(null),
    LINK_UNREACHABLE(null),
    LINK_REDIRECTED(null),
    LINK_BAD_RESPONSE(null),
    LINK_REENTRY_REQUIRED(null),
    CANCELLED(null),
    NOT_ELIGIBLE(null),
    ARCHIVE_NOT_ZIP(null),
    ARCHIVE_INCOMPLETE(null),
    ARCHIVE_FORMAT(null),
    ARCHIVE_VERSION(null),
    ARCHIVE_ENTRIES(null),
    ARCHIVE_TOO_MANY_FILES(null),
    ARCHIVE_TOO_LARGE(null),
    ARCHIVE_CHECKSUM(null),
    ARCHIVE_DAMAGED(null),
    ARCHIVE_EXPECTED_HASH(null),
    DISK_SPACE("not enough free disk space"),
    DATABASE_UNAVAILABLE("the database is unavailable"),
    STORAGE_UNAVAILABLE("the file storage is unavailable"),
    STORAGE_LIMIT("the storage limit was reached"),
    ARCHIVE_MISSING("the archive file is missing or changed"),
    UNEXPECTED("an unexpected error, see the server log");

    private final @Nullable String pauseSentence;

    ImportReason(@Nullable String pauseSentence) {
        this.pauseSentence = pauseSentence;
    }

    public Optional<String> pauseSentence() {
        return Optional.ofNullable(pauseSentence);
    }

    public boolean pausesTheImport() {
        return pauseSentence != null;
    }
}
