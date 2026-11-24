package gg.modl.backend.update.install;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.backup.UpgradeBackups;
import gg.modl.backend.infrastructure.disk.DiskSpaceFloor;
import gg.modl.backend.update.state.InstanceStateRepository;
import gg.modl.update.UpdateFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InstallPreflight {
    private static final String PROBE = ".modl-write-probe";

    private final UpgradeBackups backups;
    private final InstanceStateRepository repository;
    private final UnitOfWork unitOfWork;

    void check(UpdateFiles files, long artifactBytes, boolean backsUp) throws InstallFailedException {
        requireWritable(files.root(), InstallProblem.PREPARATION);
        if (backsUp) {
            requireWritable(backups.directory(), InstallProblem.BACKUP_DIRECTORY);
        }
        long needed = artifactBytes + (backsUp ? backups.estimatedBytes() : 0);
        try {
            if (!DiskSpaceFloor.DEFAULT.admits(Files.getFileStore(files.root()), needed)) {
                throw new InstallFailedException(InstallProblem.DISK_SPACE, "Not enough free disk space in " + files.root()
                    + " for the update and its backup (" + needed + " bytes)");
            }
        } catch (IOException e) {
            throw new InstallFailedException(InstallProblem.DISK_SPACE, "The free disk space could not be read", e);
        }
        try {
            unitOfWork.read(repository::find);
        } catch (RuntimeException e) {
            throw new InstallFailedException(InstallProblem.DATABASE, "The database is not reachable", e);
        }
    }

    private static void requireWritable(Path directory, InstallProblem problem) throws InstallFailedException {
        try {
            Files.createDirectories(directory);
            Path probe = directory.resolve(PROBE);
            Files.write(probe, new byte[] {1});
            Files.delete(probe);
        } catch (IOException e) {
            throw new InstallFailedException(problem, directory + " is not writable", e);
        }
    }
}
