package com.alessiodp.libby.relocation;

import com.alessiodp.libby.Library;
import com.alessiodp.libby.LibraryManager;
import com.alessiodp.libby.Repositories;
import com.alessiodp.libby.classloader.IsolatedClassLoader;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static com.alessiodp.libby.Util.replaceWithDots;
import static java.util.Objects.requireNonNull;

/**
 * A reflection-based helper for relocating library jars. It automatically
 * downloads and invokes Luck's Jar Relocator to perform jar relocations.
 *
 * @see <a href="https://github.com/lucko/jar-relocator">Luck's Jar Relocator</a>
 */
public class RelocationHelper {
    public static final List<Library> LIBRARIES = Collections.unmodifiableList(Arrays.asList(
            tool("org{}ow2{}asm", "asm-commons", "9.11.1", "alCV7FNyYVaniyzS79RbxOh3dU7KfCNPYSJKU9a/pmE="),
            tool("org{}ow2{}asm", "asm", "9.11.1", "rk5uENWdChUAMXdkshiBrYg/hVDjPGbmFC9wMsb7Vmw="),
            tool("me{}lucko", "jar-relocator", "1.7", "b30RhOF6kHiHl+O5suNLh/+eAr1iOFEFLXhwkHHDu4I=")
    ));

    /**
     * me.lucko.jarrelocator.JarRelocator class name for reflections
     */
    private static final String JAR_RELOCATOR_CLASS = replaceWithDots("me{}lucko{}jarrelocator{}JarRelocator");

    /**
     * me.lucko.jarrelocator.Relocation class name for reflections
     */
    private static final String RELOCATION_CLASS = replaceWithDots("me{}lucko{}jarrelocator{}Relocation");

    /**
     * Reflected constructor for creating new jar relocator instances
     */
    private final Constructor<?> jarRelocatorConstructor;

    /**
     * Reflected method for running a jar relocator
     */
    private final Method jarRelocatorRunMethod;

    /**
     * Reflected constructor for creating relocation instances
     */
    private final Constructor<?> relocationConstructor;

    /**
     * Creates a new relocation helper using the provided library manager to
     * download the dependencies required for runtime relocation.
     *
     * @param libraryManager the library manager used to download dependencies
     */
    public RelocationHelper(@NotNull LibraryManager libraryManager) {
        requireNonNull(libraryManager, "libraryManager");

        IsolatedClassLoader classLoader = new IsolatedClassLoader();
        for (Library tool : LIBRARIES) {
            classLoader.addPath(libraryManager.downloadLibrary(tool));
        }

        try {
            Class<?> jarRelocatorClass = classLoader.loadClass(JAR_RELOCATOR_CLASS);
            Class<?> relocationClass = classLoader.loadClass(RELOCATION_CLASS);

            // me.lucko.jarrelocator.JarRelocator(File, File, Collection)
            jarRelocatorConstructor = jarRelocatorClass.getConstructor(File.class, File.class, Collection.class);

            // me.lucko.jarrelocator.JarRelocator#run()
            jarRelocatorRunMethod = jarRelocatorClass.getMethod("run");

            // me.lucko.jarrelocator.Relocation(String, String, Collection, Collection)
            relocationConstructor = relocationClass.getConstructor(String.class, String.class, Collection.class, Collection.class);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Invokes the jar relocator to process the input jar and generate an
     * output jar with the provided relocation rules applied.
     *
     * @param in          input jar
     * @param out         output jar
     * @param relocations relocations to apply
     */
    public void relocate(@NotNull Path in, @NotNull Path out, @NotNull Collection<Relocation> relocations) {
        requireNonNull(in, "in");
        requireNonNull(out, "out");
        requireNonNull(relocations, "relocations");

        try {
            List<Object> rules = new LinkedList<>();
            for (Relocation relocation : relocations) {
                rules.add(relocationConstructor.newInstance(
                    relocation.getPattern(),
                    relocation.getRelocatedPattern(),
                    relocation.getIncludes(),
                    relocation.getExcludes()
                ));
            }

            jarRelocatorRunMethod.invoke(jarRelocatorConstructor.newInstance(in.toFile(), out.toFile(), rules));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Library tool(String groupId, String artifactId, String version, String checksum) {
        return Library.builder()
                .groupId(groupId)
                .artifactId(artifactId)
                .version(version)
                .checksumFromBase64(checksum)
                .fallbackRepository(Repositories.MAVEN_CENTRAL)
                .build();
    }
}
