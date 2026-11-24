package gg.modl.backend.update.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class LauncherChannel {
    private static final int GRACEFUL_STOP = 0;

    private LauncherChannel() {
    }

    public static void install(InputStream parent) {
        Thread watcher = new Thread(() -> {
            try {
                parent.transferTo(OutputStream.nullOutputStream());
            } catch (IOException closed) {
                System.err.println("modl lost the connection to its launcher (" + closed + ") and stops");
            }
            System.exit(GRACEFUL_STOP);
        }, "modl-launcher-channel");
        watcher.setDaemon(true);
        watcher.start();
    }
}
