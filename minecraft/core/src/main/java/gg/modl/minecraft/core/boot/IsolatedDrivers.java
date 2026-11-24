package gg.modl.minecraft.core.boot;

import com.alessiodp.libby.classloader.IsolatedClassLoader;
import com.alessiodp.libby.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class IsolatedDrivers {
    private static final String DEREGISTRATION = DriverDeregistration.class.getName();
    private static final String DEREGISTRATION_FILE = DriverDeregistration.class.getSimpleName() + ".class";
    private static final int BUFFER_BYTES = 4096;

    private final byte @Nullable [] deregistration;
    private final Logger logger;

    private IsolatedDrivers(byte @Nullable [] deregistration, Logger logger) {
        this.deregistration = deregistration;
        this.logger = logger;
    }

    static IsolatedDrivers read(Logger logger) {
        try (InputStream bytes = DriverDeregistration.class.getResourceAsStream(DEREGISTRATION_FILE)) {
            if (bytes == null) throw new IOException("The plugin jar has no " + DEREGISTRATION_FILE);
            return new IsolatedDrivers(readAll(bytes), logger);
        } catch (IOException unreadable) {
            logger.debug("The import database drivers will stay registered", unreadable);
            return new IsolatedDrivers(null, logger);
        }
    }

    void release(IsolatedClassLoader loader) {
        try {
            deregister(loader);
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            logger.debug("The import database drivers could not be deregistered", unavailable);
        } finally {
            close(loader);
        }
    }

    private void deregister(IsolatedClassLoader loader) throws IOException, ReflectiveOperationException {
        byte[] definition = deregistration;
        if (definition == null) return;
        Class<?> defined = loader.defineClass(DEREGISTRATION, new ByteArrayInputStream(definition));
        ((Runnable) defined.getDeclaredConstructor().newInstance()).run();
    }

    private void close(IsolatedClassLoader loader) {
        try {
            loader.close();
        } catch (IOException failure) {
            logger.debug("The import database driver files could not be closed", failure);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) output.write(buffer, 0, read);
        return output.toByteArray();
    }
}
