package gg.modl.minecraft.core.embedded;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class FakeServerPayload {
    private static final String JAR_NAME = "fake-server-3.0.0.jar";
    private static final String[] ENTRY_CLASSES = {
            "gg/modl/backend/embedded/EmbeddedServer.class",
            "gg/modl/backend/embedded/EmbeddedServer$Handle.class"
    };

    private FakeServerPayload() {
    }

    static PayloadSource write(Path directory) throws IOException {
        return builder(directory).write();
    }

    static PayloadBuilder builder(Path directory) throws IOException {
        return new PayloadBuilder(directory).packed(JAR_NAME, serverJar());
    }

    private static byte[] serverJar() throws IOException {
        ByteArrayOutputStream jar = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(jar)) {
            for (String entry : ENTRY_CLASSES) copyClass(entry, output);
        }
        return jar.toByteArray();
    }

    private static void copyClass(String entry, JarOutputStream output) throws IOException {
        output.putNextEntry(new JarEntry(entry));
        try (InputStream input = FakeServerPayload.class.getClassLoader().getResourceAsStream(entry)) {
            if (input == null) throw new IOException("missing test class " + entry);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
        output.closeEntry();
    }
}
