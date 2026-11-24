package gg.modl.minecraft.core.imports.support;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;

public final class PluginApi {
    private final ClassLoader loader;

    public PluginApi(ClassLoader loader) {
        this.loader = loader;
    }

    public @Nullable Object call(String owner, String method, @Nullable Object target) throws PluginApiException {
        return call(owner, method, target, new Class<?>[0]);
    }

    public @Nullable Object call(String owner, String method, @Nullable Object target, Class<?>[] types,
                                 Object... arguments) throws PluginApiException {
        Class<?> type = type(owner);
        try {
            return type.getMethod(method, types).invoke(target, arguments);
        } catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
            throw new PluginApiException(owner + "." + method + " failed: " + cause, cause);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            throw new PluginApiException(owner + "." + method + " is not available: " + unavailable, unavailable);
        }
    }

    public Class<?> type(String name) throws PluginApiException {
        try {
            return Class.forName(name, true, loader);
        } catch (ClassNotFoundException | LinkageError unavailable) {
            throw new PluginApiException(name + " is not available: " + unavailable, unavailable);
        }
    }

    public static final class PluginApiException extends Exception {
        PluginApiException(String detail, Throwable cause) {
            super(detail, cause);
        }

        public PluginApiException(String detail) {
            super(detail);
        }
    }
}
