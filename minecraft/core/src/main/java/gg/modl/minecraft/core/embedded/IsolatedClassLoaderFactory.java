package gg.modl.minecraft.core.embedded;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

final class IsolatedClassLoaderFactory {
    private static final String PLATFORM_CLASS_LOADER_METHOD = "getPlatformClassLoader";

    private IsolatedClassLoaderFactory() {
    }

    static URLClassLoader create(List<Path> jars) {
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < jars.size(); i++) {
            try {
                urls[i] = jars.get(i).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("Invalid library path " + jars.get(i), e);
            }
        }
        return new URLClassLoader(urls, platformClassLoader());
    }

    static ClassLoader platformClassLoader() {
        try {
            return (ClassLoader) ClassLoader.class.getMethod(PLATFORM_CLASS_LOADER_METHOD).invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The platform class loader requires Java 9 or newer", e);
        }
    }
}
