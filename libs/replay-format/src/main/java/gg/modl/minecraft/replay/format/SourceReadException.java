package gg.modl.minecraft.replay.format;

import java.io.IOException;

final class SourceReadException extends IOException {
    private final IOException failure;

    SourceReadException(IOException failure) {
        super(failure.getMessage(), failure);
        this.failure = failure;
    }

    IOException failure() {
        return failure;
    }
}
