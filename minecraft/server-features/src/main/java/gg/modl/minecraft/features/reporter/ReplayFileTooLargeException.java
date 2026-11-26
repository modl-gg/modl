package gg.modl.minecraft.features.reporter;

import java.io.IOException;

public final class ReplayFileTooLargeException extends IOException {
    private static final long serialVersionUID = 1L;

    ReplayFileTooLargeException(long sizeBytes, long limitBytes) {
        super("The replay file of " + sizeBytes + " bytes exceeds the upload limit of " + limitBytes + " bytes");
    }
}
