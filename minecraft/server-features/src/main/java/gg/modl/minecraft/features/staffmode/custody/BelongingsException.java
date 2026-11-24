package gg.modl.minecraft.features.staffmode.custody;

import org.jspecify.annotations.Nullable;

public final class BelongingsException extends Exception {
    private static final long serialVersionUID = 1L;

    public BelongingsException(String message) {
        super(message);
    }

    public BelongingsException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
