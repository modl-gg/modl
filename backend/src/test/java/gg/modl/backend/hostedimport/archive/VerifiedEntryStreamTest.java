package gg.modl.backend.hostedimport.archive;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.hostedimport.HostedArchiveFixture;
import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportReason;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class VerifiedEntryStreamTest {
    private static final byte[] ENTRY = "{\"_id\":\"p1\"}\n{\"_id\":\"p2\"}\n".getBytes(StandardCharsets.UTF_8);

    @Test
    void aFailureWhileReadingADamagedEntryIsReportedAsTheDamage() throws IOException {
        IllegalStateException unexpected = new IllegalStateException("analysis bug");
        try (VerifiedEntryStream stream = new VerifiedEntryStream(new ByteArrayInputStream(ENTRY), ENTRY.length,
            HostedArchiveFixture.sha256("other bytes".getBytes(StandardCharsets.UTF_8)))) {
            assertThat(stream.read(new byte[4], 0, 4)).isEqualTo(4);

            RuntimeException reported = stream.integrityFailureOr(unexpected);

            assertThat(reported).isInstanceOf(ImportFailure.class);
            assertThat(((ImportFailure) reported).reason()).isEqualTo(ImportReason.ARCHIVE_CHECKSUM);
            assertThat(reported.getSuppressed()).containsExactly(unexpected);
        }
    }

    @Test
    void aFailureWhileReadingAnIntactEntryStaysTheFailure() throws IOException {
        IllegalStateException unexpected = new IllegalStateException("analysis bug");
        try (VerifiedEntryStream stream = new VerifiedEntryStream(new ByteArrayInputStream(ENTRY), ENTRY.length,
            HostedArchiveFixture.sha256(ENTRY))) {
            assertThat(stream.read(new byte[4], 0, 4)).isEqualTo(4);

            assertThat(stream.integrityFailureOr(unexpected)).isSameAs(unexpected);
        }
    }
}
