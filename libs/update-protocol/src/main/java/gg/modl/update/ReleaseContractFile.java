package gg.modl.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ReleaseContractFile {
    public static final String FILE_NAME = "release-contract.json";

    private ReleaseContractFile() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Pass the output file");
        }
        write(Paths.get(args[0]));
    }

    static void write(Path output) throws IOException {
        Path directory = output.toAbsolutePath().getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        Files.write(output, json().getBytes(StandardCharsets.UTF_8));
    }

    static String json() {
        return "{\"minJava\":" + EmbeddedUpdateContract.MIN_JAVA
            + ",\"launcherApi\":" + EmbeddedUpdateContract.LAUNCHER_API
            + ",\"embeddedHostApi\":" + EmbeddedUpdateContract.HOST_API + "}\n";
    }
}
