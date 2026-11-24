package gg.modl.backend.update.guard;

import org.jspecify.annotations.Nullable;

public class UpgradeRefusedException extends IllegalStateException {
    public UpgradeRefusedException(String message) {
        super(message);
    }

    public UpgradeRefusedException(@Nullable String message, Throwable cause) {
        super(message, cause);
    }
}
