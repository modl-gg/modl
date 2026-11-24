package gg.modl.backend.hostedimport.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import gg.modl.backend.hostedimport.HostedArchiveFixture;
import gg.modl.backend.hostedimport.mapping.ArchiveAnalyzer;
import gg.modl.backend.hostedimport.mapping.ArchiveIndex;
import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportReason;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.IssueCode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveAnalyzerTest {
    private static final Instant NOW = Instant.parse("2026-11-23T00:00:00Z");
    private static final String UUID_A = "0b7e8a3c-6c55-4b3a-9d8a-111111111111";
    private static final String UUID_B = "0b7e8a3c-6c55-4b3a-9d8a-222222222222";

    @TempDir
    Path directory;

    @Test
    void aValidArchiveIsIndexedWithDuplicatePunishmentIdsScopedToTheFirstPlayer() throws IOException {
        ArchiveIndex index = analyse(new HostedArchiveFixture()
            .line("players", player("p1", UUID_A, "shared"))
            .line("players", player("p2", UUID_B, "shared"))
            .line("settings", "{\"_id\":{\"$oid\":\"665f1c2e8b3e4a0012345678\"},\"type\":\"general\",\"data\":{\"serverDisplayName\":\"Old\"},\"version\":1}")
            .line("settings", "{\"_id\":{\"$oid\":\"665f1c2e8b3e4a0012345679\"},\"type\":\"general\",\"data\":{\"serverDisplayName\":\"New\"},\"version\":2}")
            .line("command_logs", "{\"_id\":{\"$oid\":\"665f1c2e8b3e4a001234567a\"},\"uuid\":\"" + UUID_A + "\",\"command\":\"/login hunter2\",\"timestamp\":{\"$numberLong\":\"1732270000000\"}}")
            .object("evidence/a.png", "png"));

        assertThat(index.uuidOfSourcePlayer("p1")).isEqualTo(UUID_A);
        assertThat(index.uuidOfSourcePlayer("p2")).isEqualTo(UUID_B);
        assertThat(index.uuidOfSourcePlayer("p3")).isNull();
        assertThat(index.punishmentKeepers().isKeptBy("shared", UUID_A)).isTrue();
        assertThat(index.punishmentKeepers().isKeptBy("shared", UUID_B)).isFalse();
        assertThat(index.setting("general").flatMap(document -> document.document("data")).map(data -> data.string("serverDisplayName")))
            .contains("New");
        assertThat(index.analysisTally().count(ImportSubject.PLAYERS).total()).isEqualTo(2);
        assertThat(index.analysisTally().issues()).containsKeys(IssueCode.COMMAND_REDACTED, IssueCode.COLLECTION_EXCLUDED,
            IssueCode.FILE_SKIPPED_BY_EXPORT);
        assertThat(index.keyPlan().forOriginalKey(HostedArchiveFixture.PREFIX + "evidence/a.png"))
            .map(planned -> planned.localKey()).contains("modl_instance/evidence/a.png");
    }

    @Test
    void readingTheManifestLeavesItsEntryOpenSoItsChecksumCanBeVerified() throws IOException {
        Path file = new HostedArchiveFixture().line("players", player("p1", UUID_A, "only")).write(directory);

        try (HostedArchive archive = HostedArchive.open(file); VerifiedEntryStream stream = archive.openManifest()) {
            ManifestReader.read(stream);
            stream.drainAndVerify();

            assertThat(stream.bytesRead()).isPositive();
        }
    }

    @Test
    void aFormBLineIsDecodedAsBsonAndItsDollarKeysAreData() throws IOException {
        byte[] bson = bsonDocumentWithString("$where", "1 == 1");
        String line = "{\"$modlBson\":\"" + Base64.getEncoder().encodeToString(bson) + "\"}";
        ArchiveIndex index = analyse(new HostedArchiveFixture().line("logs", line));

        assertThat(index.analysisTally().issues()).doesNotContainKey(IssueCode.RECORD_UNREADABLE);
        assertThat(index.retention().auditLogs().total()).isEqualTo(1);
    }

    @Test
    void aChangedCollectionFailsTheChecksum() throws IOException {
        assertFailure(new HostedArchiveFixture().line("players", player("p1", UUID_A, "x")).wrongSha256("players"),
            ImportReason.ARCHIVE_CHECKSUM);
    }

    @Test
    void aPunishmentWithoutAnEnforcementCategoryIsReadButNotCountedAsEnforced() throws IOException {
        ArchiveIndex index = analyse(new HostedArchiveFixture()
            .line("players", player("p1", UUID_A, "ban"))
            .line("players", playerWithPunishmentData("p2", UUID_B, "kick", "{}")));

        assertThat(index.enforcedPunishmentsByOrdinal()).containsExactly(entry(2, 1L));
        assertThat(index.analysisTally().count(ImportSubject.PUNISHMENTS).total()).isEqualTo(2);
    }

    @Test
    void aDamagedCollectionIsReportedAsDamagedWhateverItsDocumentsContain() throws IOException {
        assertFailure(new HostedArchiveFixture()
                .line("players", playerWithPunishmentData("p1", UUID_A, "kick", "{}"))
                .wrongSha256("players"),
            ImportReason.ARCHIVE_CHECKSUM);
    }

    @Test
    void aWrongDocumentCountFailsTheArchive() throws IOException {
        assertFailure(new HostedArchiveFixture().line("players", player("p1", UUID_A, "x")).wrongDocumentCount("players", 2),
            ImportReason.ARCHIVE_CHECKSUM);
    }

    @Test
    void anEntryOutsideTheManifestFailsTheArchive() throws IOException {
        assertFailure(new HostedArchiveFixture().extraEntry("storage/../evil.sh"), ImportReason.ARCHIVE_ENTRIES);
    }

    @Test
    void aNewerFormatVersionIsRefused() throws IOException {
        assertFailure(new HostedArchiveFixture().formatVersion(2), ImportReason.ARCHIVE_VERSION);
    }

    @Test
    void aFileThatIsNotAZipIsRefused() throws IOException {
        Path file = directory.resolve("not.zip");
        Files.writeString(file, "this is not a zip archive at all, but it is long enough", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> HostedArchive.open(file)).isInstanceOf(ImportFailure.class)
            .extracting(failure -> ((ImportFailure) failure).reason()).isEqualTo(ImportReason.ARCHIVE_NOT_ZIP);
    }

    @Test
    void anUnreadableLineIsSkippedAndReported() throws IOException {
        ArchiveIndex index = analyse(new HostedArchiveFixture().line("logs", "{\"_id\":{\"$unknownWrapper\":1}}"));
        assertThat(index.analysisTally().issues()).containsKey(IssueCode.RECORD_UNREADABLE);
    }

    private ArchiveIndex analyse(HostedArchiveFixture fixture) throws IOException {
        Path file = fixture.write(directory);
        try (HostedArchive archive = HostedArchive.open(file)) {
            ArchiveManifest manifest;
            try (VerifiedEntryStream stream = archive.openManifest()) {
                manifest = ManifestReader.read(stream);
            }
            return new ArchiveAnalyzer(archive, manifest, NOW, 365, () -> false).analyse(true, done -> { });
        }
    }

    private void assertFailure(HostedArchiveFixture fixture, ImportReason reason) {
        assertThatThrownBy(() -> analyse(fixture)).isInstanceOf(ImportFailure.class)
            .extracting(failure -> ((ImportFailure) failure).reason()).isEqualTo(reason);
    }

    static String player(String id, String uuid, String punishmentId) {
        return playerWithPunishmentData(id, uuid, punishmentId, "{\"enforcementCategory\":\"BAN\"}");
    }

    static String playerWithPunishmentData(String id, String uuid, String punishmentId, String data) {
        return "{\"_id\":\"" + id + "\",\"minecraftUuid\":\"" + uuid + "\",\"usernames\":[{\"username\":\"Steve\"}],"
            + "\"ipAddresses\":[{\"ipAddress\":\"203.0.113.7\",\"firstLogin\":{\"$date\":\"2026-11-01T00:00:00Z\"},"
            + "\"logins\":[{\"$date\":\"2026-11-02T00:00:00Z\"},{\"$date\":\"2026-11-03T00:00:00Z\"}]}],"
            + "\"punishments\":[{\"id\":\"" + punishmentId + "\",\"typeOrdinal\":2,\"issued\":{\"$date\":\"2026-11-01T00:00:00Z\"},"
            + "\"data\":" + data + "}]}";
    }

    static byte[] bsonDocumentWithString(String key, String value) {
        byte[] name = key.getBytes(StandardCharsets.UTF_8);
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        int length = 4 + 1 + name.length + 1 + 4 + text.length + 1 + 1;
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(length).put((byte) 0x02).put(name).put((byte) 0).putInt(text.length + 1).put(text).put((byte) 0).put((byte) 0);
        return buffer.array();
    }
}
