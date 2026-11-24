package gg.modl.minecraft.core.embedded;

@FunctionalInterface
interface ServerEntrypoint {
    AutoCloseable start(ClassLoader serverClassLoader) throws Exception;
}
