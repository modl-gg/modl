package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.connection.BackendEndpoint;
import gg.modl.minecraft.core.util.PluginLogger;

import java.nio.file.Path;
import java.util.Collections;

public final class TestEmbeddedHandles {
    private TestEmbeddedHandles() {
    }

    public static EmbeddedServerHandle running(Path folder, BackendEndpoint endpoint, PluginLogger logger, Runnable onClose)
            throws Exception {
        EmbeddedServerLock lock = EmbeddedServerLock.acquire(folder.resolve("server.lock"), () -> false, logger);
        return EmbeddedServerHandle.start(Collections.<Path>emptyList(), lock, endpoint, logger, loader -> onClose::run);
    }
}
