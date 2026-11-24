package gg.modl.minecraft.core.embedded;

import java.io.IOException;
import java.util.function.BooleanSupplier;

public interface BasePayload {
    VerifiedArchive open(BooleanSupplier cancelled) throws IOException;

    VerifiedArchive openInstalled() throws IOException;
}
