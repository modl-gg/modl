package gg.modl.backend.database.sql;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

final class IsolatedLoaders {
    private IsolatedLoaders() {
    }

    static URLClassLoader create() {
        URL[] urls = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .filter(entry -> !entry.isBlank())
            .map(IsolatedLoaders::url)
            .toArray(URL[]::new);
        return new URLClassLoader("modl-isolated", urls, ClassLoader.getPlatformClassLoader());
    }

    static <T> T call(ClassLoader loader, Callable<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().unstarted(() -> {
            try {
                result.set(work.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        thread.setContextClassLoader(loader);
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private static URL url(String entry) {
        try {
            return Path.of(entry).toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(entry, e);
        }
    }
}
