package gg.modl.backend.instance.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SetupCodeFileTest {
    @TempDir
    Path workingDirectory;

    @Test
    void aFileInsideTheWorkingDirectoryIsNamedRelativelyEverywhere() throws IOException {
        SetupCodeFile file = new SetupCodeFile(workingDirectory.resolve("plugins/modl/setup-code.txt"), workingDirectory);

        assertThat(file.write("modl setup code: ABCD-EFGH\n")).contains(Path.of("plugins/modl/setup-code.txt").toString());
        assertThat(Files.readString(workingDirectory.resolve("plugins/modl/setup-code.txt"))).isEqualTo("modl setup code: ABCD-EFGH\n");
    }

    @Test
    void aFileOutsideTheWorkingDirectoryIsShownInFullOnTheConsole(@TempDir Path elsewhere) {
        SetupCodeFile file = new SetupCodeFile(elsewhere.resolve("setup-code.txt"), workingDirectory);

        assertThat(file.write("code")).contains(elsewhere.resolve("setup-code.txt").toAbsolutePath().normalize().toString());
    }

    @Test
    void theFileIsReadableOnlyByItsOwner() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        SetupCodeFile file = new SetupCodeFile(workingDirectory.resolve("setup-code.txt"), workingDirectory);

        file.write("code");

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(workingDirectory.resolve("setup-code.txt"))))
            .isEqualTo("rw-------");
    }

    @Test
    void rewritingReplacesTheFileAndLeavesNoTemporaryFilesBehind() throws IOException {
        SetupCodeFile file = new SetupCodeFile(workingDirectory.resolve("setup-code.txt"), workingDirectory);

        file.write("first");
        file.write("second");

        assertThat(Files.readString(workingDirectory.resolve("setup-code.txt"))).isEqualTo("second");
        try (Stream<Path> entries = Files.list(workingDirectory)) {
            assertThat(entries.map(entry -> entry.getFileName().toString())).containsExactly("setup-code.txt");
        }
    }

    @Test
    void deletingRemovesTheFile() {
        SetupCodeFile file = new SetupCodeFile(workingDirectory.resolve("setup-code.txt"), workingDirectory);
        file.write("code");

        file.delete();

        assertThat(Files.exists(workingDirectory.resolve("setup-code.txt"))).isFalse();
    }

    @Test
    void anUnwritableLocationReportsNoFileInsteadOfFailing() throws IOException {
        Path blocker = Files.writeString(workingDirectory.resolve("blocker"), "not a directory");
        SetupCodeFile file = new SetupCodeFile(blocker.resolve("setup-code.txt"), workingDirectory);

        assertThat(file.write("code")).isEmpty();
    }
}
