package gg.modl.minecraft.replay.format;

import java.io.IOException;

public class ReplayFormatException extends IOException {
    public ReplayFormatException(String message) {
        super(message);
    }

    public ReplayFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
