package gg.modl.backend.storage.service;

public enum UploadConfirmation {
    RECORDED,
    ALREADY_RECORDED,
    EXPIRED;

    public boolean recorded() {
        return this == RECORDED || this == ALREADY_RECORDED;
    }
}
