package gg.modl.backend.database.sql;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class ChildJvm implements AutoCloseable {
    private static final Duration LINE_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(30);
    private static final String END_OF_OUTPUT = "\u0000end of output";

    private final Process process;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final List<String> transcript = new ArrayList<>();

    private ChildJvm(Process process) {
        this.process = process;
        Thread.ofPlatform().daemon().start(this::readOutput);
    }

    public static ChildJvm start(Class<?> mainClass, String... arguments) {
        List<String> command = new ArrayList<>(List.of(javaExecutable(), "-cp", System.getProperty("java.class.path"),
            mainClass.getName()));
        command.addAll(List.of(arguments));
        try {
            return new ChildJvm(new ProcessBuilder(command).redirectErrorStream(true).start());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start " + mainClass.getName(), e);
        }
    }

    static String javaExecutable() {
        return ProcessHandle.current().info().command().orElseThrow();
    }

    ProcessHandle handle() {
        return process.toHandle();
    }

    public String awaitLineStartingWith(String... prefixes) throws InterruptedException {
        long deadline = System.nanoTime() + LINE_TIMEOUT.toNanos();
        while (true) {
            String line = lines.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (line == null || line.equals(END_OF_OUTPUT)) {
                throw new IllegalStateException("The child JVM printed none of " + List.of(prefixes) + ":\n" + transcript());
            }
            for (String prefix : prefixes) {
                if (line.startsWith(prefix)) {
                    return line;
                }
            }
        }
    }

    void kill() throws InterruptedException {
        process.destroyForcibly().waitFor(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() throws InterruptedException {
        kill();
    }

    private void readOutput() {
        try (BufferedReader output = process.inputReader()) {
            String line;
            while ((line = output.readLine()) != null) {
                remember(line);
                lines.add(line);
            }
        } catch (IOException e) {
            remember("output failed: " + e.getMessage());
        } finally {
            lines.add(END_OF_OUTPUT);
        }
    }

    private synchronized void remember(String line) {
        transcript.add(line);
    }

    private synchronized String transcript() {
        return String.join("\n", transcript);
    }
}
