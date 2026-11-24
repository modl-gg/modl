package gg.modl.minecraft.core.util;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

public final class JavaRuntime {
    private static final String SPECIFICATION_VERSION = "java.specification.version";
    private static final String LEGACY_PREFIX = "1.";
    private static final String CLASS_FILE_SUFFIX = ".class";
    private static final int CLASS_FILE_MAGIC = 0xCAFEBABE;
    private static final int JAVA_1_0_MAJOR_VERSION_OFFSET = 44;

    private JavaRuntime() {
    }

    public static int currentFeatureVersion() {
        return featureVersion(System.getProperty(SPECIFICATION_VERSION));
    }

    public static int featureVersionRequiredBy(ClassLoader loader, String className) {
        String resource = className.replace('.', '/') + CLASS_FILE_SUFFIX;
        try (InputStream classFile = loader.getResourceAsStream(resource)) {
            if (classFile == null) throw new IllegalStateException(className + " is not on the class path");
            return featureVersionOf(new DataInputStream(classFile), resource);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read " + resource, unreadable);
        }
    }

    static int featureVersion(String specificationVersion) {
        if (specificationVersion == null) return 0;
        String version = specificationVersion.trim();
        if (version.startsWith(LEGACY_PREFIX)) version = version.substring(LEGACY_PREFIX.length());
        int dot = version.indexOf('.');
        if (dot >= 0) version = version.substring(0, dot);
        try {
            return Integer.parseInt(version);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int featureVersionOf(DataInputStream classFile, String resource) throws IOException {
        if (classFile.readInt() != CLASS_FILE_MAGIC) throw new IllegalStateException(resource + " is not a class file");
        classFile.readUnsignedShort();
        return classFile.readUnsignedShort() - JAVA_1_0_MAJOR_VERSION_OFFSET;
    }
}
