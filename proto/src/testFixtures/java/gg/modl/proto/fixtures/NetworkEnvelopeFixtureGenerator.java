package gg.modl.proto.fixtures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class NetworkEnvelopeFixtureGenerator {
    private NetworkEnvelopeFixtureGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Usage: NetworkEnvelopeFixtureGenerator <fixture directory>");
        Path directory = Paths.get(args[0]);
        Files.createDirectories(directory);
        for (NetworkEnvelopeFixtures fixture : NetworkEnvelopeFixtures.values()) {
            Files.write(directory.resolve(fixture.fileName()), NetworkEnvelopeFixtureMessages.of(fixture).toByteArray());
        }
    }
}
