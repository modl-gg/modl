package gg.modl.proto.fixtures;

import gg.modl.proto.modl.v1.RealtimeEnvelope;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class NetworkEnvelopeFixtureFilesTest {

    @Test
    void everyCommittedFixtureParsesAndIsExactlyWhatTheGeneratorWrites() {
        for (NetworkEnvelopeFixtures fixture : NetworkEnvelopeFixtures.values()) {
            RealtimeEnvelope expected = NetworkEnvelopeFixtureMessages.of(fixture);
            assertEquals(expected, fixture.envelope(), fixture.fileName());
            assertArrayEquals(expected.toByteArray(), fixture.bytes(),
                    fixture.fileName() + " differs from NetworkEnvelopeFixtureMessages; run ./gradlew :proto:regenerateNetworkFixtures "
                            + "only for a deliberate contract change");
        }
    }

    @Test
    void theGeneratorWritesOneFilePerFixture() throws IOException {
        Path directory = Files.createTempDirectory("network-fixtures");
        NetworkEnvelopeFixtureGenerator.main(new String[] {directory.toString()});

        for (NetworkEnvelopeFixtures fixture : NetworkEnvelopeFixtures.values()) {
            assertArrayEquals(fixture.bytes(), Files.readAllBytes(directory.resolve(fixture.fileName())), fixture.fileName());
        }
        try (Stream<Path> written = Files.list(directory)) {
            assertEquals(NetworkEnvelopeFixtures.values().length, written.count());
        }
    }

    @Test
    void aMemberReportsACountWhileACoreReportsItsPlayers() {
        assertNotEquals(NetworkEnvelopeFixtures.CORE_HOSTED_PLAYERS.envelope(), NetworkEnvelopeFixtures.MEMBER_HOSTED_PLAYERS.envelope());
        assertEquals(0, NetworkEnvelopeFixtures.MEMBER_HOSTED_PLAYERS.envelope().getHostedPlayers().getJoinedUuidsCount());
    }
}
