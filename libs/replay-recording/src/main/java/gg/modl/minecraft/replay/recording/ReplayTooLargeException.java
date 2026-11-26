package gg.modl.minecraft.replay.recording;

import java.io.IOException;

final class ReplayTooLargeException extends IOException {
    private static final long serialVersionUID = 1L;

    private final Overflow overflow;

    ReplayTooLargeException(String limit, Overflow overflow) {
        super("The replay would exceed the upload limit on " + limit);
        this.overflow = overflow;
    }

    Overflow overflow() {
        return overflow;
    }

    enum Overflow {
        HISTORY(true, false),
        TERRAIN(false, true),
        SIZE(true, true);

        private final boolean shortensWindow;
        private final boolean narrowsRadius;

        Overflow(boolean shortensWindow, boolean narrowsRadius) {
            this.shortensWindow = shortensWindow;
            this.narrowsRadius = narrowsRadius;
        }

        boolean shortensWindow() {
            return shortensWindow;
        }

        boolean narrowsRadius() {
            return narrowsRadius;
        }
    }
}
