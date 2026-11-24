package gg.modl.backend.embedded;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.context.ConfigurableApplicationContext;

final class EmbeddedServerHandle implements AutoCloseable, Supplier<Map<String, String>> {
    private final ConfigurableApplicationContext context;
    private final EmbeddedServerDescriptor descriptor;
    private final ClassLoader serverClassLoader;
    private final ServerResources serverResources;
    private final AtomicBoolean closed = new AtomicBoolean();

    EmbeddedServerHandle(ConfigurableApplicationContext context, ClassLoader serverClassLoader, ServerResources serverResources) {
        this.context = context;
        this.descriptor = new EmbeddedServerDescriptor(context);
        this.serverClassLoader = serverClassLoader;
        this.serverResources = serverResources;
    }

    @Override
    public Map<String, String> get() {
        return ServerClassLoader.call(serverClassLoader, descriptor::describe);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ServerClassLoader.call(serverClassLoader, () -> {
            try {
                context.close();
            } finally {
                serverResources.release();
            }
            return null;
        });
    }
}
