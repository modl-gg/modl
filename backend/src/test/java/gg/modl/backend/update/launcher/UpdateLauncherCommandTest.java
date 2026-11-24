package gg.modl.backend.update.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.update.JacksonJsonMaps;
import gg.modl.update.ArtifactKind;
import gg.modl.update.ReleaseKeys;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.TransactionKind;
import gg.modl.update.UpdateFiles;
import gg.modl.update.UpdatePhase;
import gg.modl.update.UpdateTransaction;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateLauncherCommandTest {
    private static final ReleaseVersion BASE = ReleaseVersion.parse("3.0.0");
    private static final ReleaseVersion TARGET = ReleaseVersion.parse("3.0.1");
    private static final int IN_PROCESS = -1;

    @TempDir
    Path temp;

    @Test
    void aCommandBesideAStagedUpdateRunsTheBaseVersionAndLeavesTheUpdateUntouched() throws Exception {
        UpdateFiles files = new UpdateFiles(temp, JacksonJsonMaps.INSTANCE);
        UpdateTransaction staged = UpdateTransaction.builder("staged", TransactionKind.JAR, BASE, TARGET, Instant.EPOCH)
            .managed(true)
            .toArtifact(files.relativeArtifact(TARGET, ArtifactKind.SERVER))
            .build();
        files.writePending(staged);
        byte[] before = Files.readAllBytes(files.pendingFile());

        int code = launcher(files).supervise(new String[] {"restore", "--input", "backup.zip"}, true);

        assertThat(code).isEqualTo(IN_PROCESS);
        assertThat(Files.readAllBytes(files.pendingFile())).isEqualTo(before);
        assertThat(files.readPending().attempts()).isZero();
        assertThat(files.readPending().phase()).isEqualTo(UpdatePhase.STAGED);
    }

    @Test
    void aCommandBesideAnUnfinishedMigrationRunsTheBaseVersionAndRewritesNothing() throws Exception {
        UpdateFiles files = new UpdateFiles(temp, JacksonJsonMaps.INSTANCE);
        files.writePending(UpdateTransaction.builder("migrating", TransactionKind.JAR, BASE, TARGET, Instant.EPOCH)
            .managed(true)
            .toArtifact(files.relativeArtifact(TARGET, ArtifactKind.SERVER))
            .phase(UpdatePhase.MIGRATING)
            .build());
        byte[] before = Files.readAllBytes(files.pendingFile());

        int code = launcher(files).supervise(new String[] {"backup"}, true);

        assertThat(code).isEqualTo(IN_PROCESS);
        assertThat(Files.readAllBytes(files.pendingFile())).isEqualTo(before);
    }

    private UpdateLauncher launcher(UpdateFiles files) {
        PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        return new UpdateLauncher(files, BASE, ReleaseKeys.parse(""), temp.resolve("modl-server-3.0.0.jar"), out);
    }
}
