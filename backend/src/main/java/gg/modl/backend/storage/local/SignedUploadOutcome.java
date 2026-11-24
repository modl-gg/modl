package gg.modl.backend.storage.local;

public enum SignedUploadOutcome {
    STORED,
    INVALID_SIGNATURE,
    CONTENT_TYPE_MISMATCH,
    LENGTH_REQUIRED,
    TOO_LARGE,
    LENGTH_MISMATCH,
    ALREADY_EXISTS
}
