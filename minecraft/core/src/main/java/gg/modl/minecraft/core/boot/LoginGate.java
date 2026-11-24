package gg.modl.minecraft.core.boot;

import org.jspecify.annotations.Nullable;

public final class LoginGate {
    private volatile @Nullable String deniedMessage = "";

    LoginGate() {
    }

    public boolean isOpen() {
        return deniedMessage == null;
    }

    public String deniedMessage() {
        String message = deniedMessage;
        return message == null ? "" : message;
    }

    void deny(@Nullable String message) {
        deniedMessage = message == null ? "" : message;
    }

    void open() {
        deniedMessage = null;
    }
}
