package gg.modl.backend.database.sql;

import gg.modl.backend.support.sql.DialectTarget;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Path;

final class IsolatedKernel implements AutoCloseable {
    private URLClassLoader loader;
    private Object host;

    private IsolatedKernel(URLClassLoader loader, Object host) {
        this.loader = loader;
        this.host = host;
    }

    static IsolatedKernel create() throws Exception {
        URLClassLoader loader = IsolatedLoaders.create();
        Object host = IsolatedLoaders.call(loader,
            () -> Class.forName(IsolatedKernelHost.class.getName(), true, loader).getConstructor().newInstance());
        if (host.getClass() == IsolatedKernelHost.class) {
            throw new IllegalStateException("The host was loaded by the test class loader instead of the isolated one");
        }
        return new IsolatedKernel(loader, host);
    }

    void start(Path dataDirectory) throws Exception {
        invoke("start", new Class<?>[] {Path.class}, dataDirectory);
    }

    void startExternal(Path dataDirectory, DialectTarget target, SqlDatabaseProperties database) throws Exception {
        invoke("startExternal", new Class<?>[] {Path.class, String.class, String.class, String.class, String.class},
            dataDirectory, target.name(), database.url(), database.username(), database.password());
    }

    void writeSettings(String type) throws Exception {
        invoke("writeSettings", new Class<?>[] {String.class}, type);
    }

    boolean hasSettings(String type) throws Exception {
        return (Boolean) invoke("hasSettings", new Class<?>[] {String.class}, type);
    }

    boolean hashPassword(String password) throws Exception {
        return (Boolean) invoke("hashPassword", new Class<?>[] {String.class}, password);
    }

    void holdConnection() throws Exception {
        invoke("holdConnection", new Class<?>[0]);
    }

    void releaseHeldConnection() throws Exception {
        invoke("releaseHeldConnection", new Class<?>[0]);
    }

    long registeredDrivers() throws Exception {
        return (Long) invoke("registeredDrivers", new Class<?>[0]);
    }

    void stop() throws Exception {
        invoke("close", new Class<?>[0]);
    }

    WeakReference<ClassLoader> abandon() throws IOException {
        WeakReference<ClassLoader> reference = new WeakReference<>(loader);
        close();
        return reference;
    }

    @Override
    public void close() throws IOException {
        if (loader != null) {
            loader.close();
        }
        loader = null;
        host = null;
    }

    private Object invoke(String name, Class<?>[] parameterTypes, Object... arguments) throws Exception {
        Method method = host.getClass().getMethod(name, parameterTypes);
        return IsolatedLoaders.call(loader, () -> {
            try {
                return method.invoke(host, arguments);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException(CauseChain.messages(e.getCause()));
            }
        });
    }
}
