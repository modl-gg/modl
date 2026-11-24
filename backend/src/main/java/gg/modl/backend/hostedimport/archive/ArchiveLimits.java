package gg.modl.backend.hostedimport.archive;

public final class ArchiveLimits {
    public static final long MAX_ARCHIVE_BYTES = 100L * 1024 * 1024 * 1024;
    public static final long MAX_ENTRIES = 300_000;
    public static final long MAX_CENTRAL_DIRECTORY_BYTES = 128L * 1024 * 1024;
    public static final long MAX_MANIFEST_BYTES = 600L * 1024 * 1024;
    public static final long MAX_SERVER_JSON_BYTES = 64L * 1024;
    public static final long MAX_COLLECTION_BYTES = 64L * 1024 * 1024 * 1024;
    public static final int MAX_LINE_BYTES = 128 * 1024 * 1024;
    public static final long MAX_DOCUMENT_HEAP_BYTES = 128L * 1024 * 1024;
    public static final int MAX_DEPTH = 128;
    public static final int MAX_BSON_DOCUMENT_BYTES = 16 * 1024 * 1024 + 16 * 1024;
    public static final int FORMAT_VERSION = 1;
    public static final String FORMAT = "modl-hosted-export";

    private ArchiveLimits() {
    }
}
