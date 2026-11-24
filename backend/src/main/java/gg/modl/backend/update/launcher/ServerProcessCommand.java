package gg.modl.backend.update.launcher;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class ServerProcessCommand {
    private static final List<String> DROPPED_PREFIXES = List.of("-agentlib:jdwp", "-Xrunjdwp",
        "-Dcom.sun.management.jmxremote.port=", "-Dcom.sun.management.jmxremote.rmi.port=");

    private ServerProcessCommand() {
    }

    public static List<String> forCurrentJvm(Path jar, String[] programArguments) {
        return build(javaCommand(), ManagementFactory.getRuntimeMXBean().getInputArguments(), jar, programArguments);
    }

    static List<String> build(String java, List<String> jvmArguments, Path jar, String[] programArguments) {
        List<String> command = new ArrayList<>();
        command.add(java);
        jvmArguments.stream().filter(ServerProcessCommand::kept).forEach(command::add);
        command.add("-jar");
        command.add(jar.toAbsolutePath().toString());
        command.addAll(Arrays.asList(programArguments));
        return List.copyOf(command);
    }

    static boolean kept(String argument) {
        return DROPPED_PREFIXES.stream().noneMatch(argument::startsWith);
    }

    static String javaCommand() {
        return ProcessHandle.current().info().command().orElseGet(ServerProcessCommand::javaFromHome);
    }

    static String javaFromHome() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }
}
