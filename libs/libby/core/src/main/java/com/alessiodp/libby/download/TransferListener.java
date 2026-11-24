package com.alessiodp.libby.download;

@FunctionalInterface
public interface TransferListener {
    TransferListener NONE = attemptBytes -> {
    };

    void transferred(long attemptBytes);
}
