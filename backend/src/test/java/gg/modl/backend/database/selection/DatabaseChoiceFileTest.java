package gg.modl.backend.database.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import gg.modl.backend.database.DatabaseDialect;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseChoiceFileTest {
    private static final DatabaseConnection POSTGRES = new DatabaseConnection(DatabaseDialect.POSTGRESQL, "db", 5432, "modl", true,
        "modl", "secret");

    @TempDir
    Path directory;

    @Test
    void aChoiceSurvivesTheRoundTrip() throws IOException {
        DatabaseChoiceFile file = new DatabaseChoiceFile(directory);
        PanelDatabaseChoice choice = PanelDatabaseChoice.initial().copying(POSTGRES.location(), "move-token").movedTo(POSTGRES);

        file.write(choice);

        assertThat(file.read()).contains(choice);
        assertThat(file.read().orElseThrow().selected().password()).isEqualTo("secret");
        assertThat(file.read().orElseThrow().pending()).isTrue();
    }

    @Test
    void theFileIsReadableOnlyByItsOwner() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        DatabaseChoiceFile file = new DatabaseChoiceFile(directory);

        file.write(PanelDatabaseChoice.initial().movedTo(POSTGRES));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file.path()))).isEqualTo("rw-------");
        try (var listing = Files.list(directory)) {
            assertThat(listing.map(Path::getFileName).map(Path::toString)).containsExactly(DatabaseChoiceFile.FILE_NAME);
        }
    }

    @Test
    void anAbsentFileIsNoChoiceAndADamagedOneIsAnError() throws IOException {
        DatabaseChoiceFile file = new DatabaseChoiceFile(directory);
        assertThat(file.read()).isEmpty();

        Files.writeString(file.path(), "{\"format\":1,\"selected\":{\"dialect\":\"MYSQL\",\"host\":\"db/x\",\"port\":1,"
            + "\"database\":\"modl\",\"tls\":false,\"username\":\"u\"},\"adoption\":\"ACTIVE\"}");
        assertThatThrownBy(file::read).isInstanceOf(IOException.class).hasMessageContaining(DatabaseChoiceFile.FILE_NAME);

        Files.writeString(file.path(), "not json");
        assertThatThrownBy(file::read).isInstanceOf(IOException.class);
    }

    @Test
    void aChoiceDescribesItselfWithoutThePassword() {
        assertThat(PanelDatabaseChoice.initial().movedTo(POSTGRES).toString()).doesNotContain("secret");
    }
}
