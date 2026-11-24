package gg.modl.minecraft.core.embedded;

import gg.modl.update.VerifiedCopy;

public final class VerifiedArchive implements AutoCloseable {
    private final EmbeddedPayload payload;
    private final VerifiedCopy copy;

    VerifiedArchive(EmbeddedPayload payload, VerifiedCopy copy) {
        this.payload = payload;
        this.copy = copy;
    }

    EmbeddedPayload payload() {
        return payload;
    }

    @Override
    public void close() {
        copy.close();
    }
}
