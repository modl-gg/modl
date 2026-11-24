package gg.modl.backend.embedded;

import java.util.function.Supplier;

final class ServerClassLoader {

    private ServerClassLoader() {
    }

    static <T> T call(ClassLoader serverClassLoader, Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(serverClassLoader);
        try {
            return action.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
