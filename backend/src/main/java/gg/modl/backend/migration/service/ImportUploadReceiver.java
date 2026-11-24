package gg.modl.backend.migration.service;

import gg.modl.backend.infrastructure.disk.DiskSpaceFloor;
import gg.modl.backend.infrastructure.disk.DurableFiles;
import gg.modl.backend.infrastructure.exception.ExternalServiceException;
import gg.modl.backend.migration.data.ImportEnding;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamSource;

@Slf4j
public class ImportUploadReceiver {
    static final Duration ACTIVE_CHECK_INTERVAL = Duration.ofSeconds(5);
    static final Duration DEADLINE = Duration.ofHours(2);

    private final ImportFileIntake intake;
    private final ImportUploadFiles files;
    private final DiskSpaceFloor diskSpaceFloor;
    private final long maxBytes;
    private final Clock clock;

    public ImportUploadReceiver(ImportFileIntake intake, ImportUploadFiles files, DiskSpaceFloor diskSpaceFloor,
                                long maxBytes, Clock clock) {
        this.intake = intake;
        this.files = files;
        this.diskSpaceFloor = diskSpaceFloor;
        this.maxBytes = maxBytes;
        this.clock = clock;
    }

    public void receive(String jobId, long declaredLength, InputStreamSource body) {
        if (!intake.awaitsFile(jobId)) {
            throw ImportUploadException.notAccepted();
        }
        if (declaredLength <= 0) {
            throw ImportUploadException.lengthRequired();
        }
        if (declaredLength > maxBytes) {
            intake.refuse(jobId, ImportEnding.FILE_TOO_LARGE);
            throw ImportUploadException.tooLarge(maxBytes);
        }
        if (!fitsOnDisk(declaredLength)) {
            intake.refuse(jobId, ImportEnding.UPLOAD_FAILED);
            throw ImportUploadException.diskFull();
        }
        if (!intake.claim(jobId)) {
            throw ImportUploadException.notAccepted();
        }
        boolean accepted = false;
        try {
            storeAndAccept(jobId, declaredLength, body);
            accepted = true;
        } finally {
            if (!accepted) {
                abandon(jobId);
            }
        }
    }

    private boolean fitsOnDisk(long bytes) {
        try {
            return diskSpaceFloor.admits(files.store(), bytes);
        } catch (IOException e) {
            throw new ExternalServiceException("The import upload directory is not usable", e);
        }
    }

    private void storeAndAccept(String jobId, long declaredLength, InputStreamSource body) {
        try {
            Path partial = files.createPartial(jobId);
            try {
                copy(jobId, declaredLength, body, partial);
                files.publish(partial, jobId);
            } finally {
                deleteQuietly(partial);
            }
        } catch (IOException e) {
            throw new ExternalServiceException("The import file could not be received", e);
        }
        if (!intake.accept(jobId)) {
            deleteQuietly(files.accepted(jobId));
            throw ImportUploadException.notAccepted();
        }
    }

    private void copy(String jobId, long declaredLength, InputStreamSource body, Path partial) throws IOException {
        try (InputStream data = new GuardedBody(body.getInputStream(), jobId, clock.instant())) {
            if (DurableFiles.copyAtMost(data, partial, declaredLength) != declaredLength) {
                throw ImportUploadException.incomplete();
            }
        }
    }

    private void abandon(String jobId) {
        if (intake.isReceiving(jobId)) {
            deleteQuietly(files.accepted(jobId));
            intake.reopen(jobId);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete import upload {}", file, e);
        }
    }

    private final class GuardedBody extends FilterInputStream {
        private final String jobId;
        private final Instant deadline;
        private Instant nextCheck;

        private GuardedBody(InputStream body, String jobId, Instant openedAt) {
            super(body);
            this.jobId = jobId;
            this.deadline = openedAt.plus(DEADLINE);
            this.nextCheck = openedAt.plus(ACTIVE_CHECK_INTERVAL);
        }

        @Override
        public int read() throws IOException {
            requireStillWanted();
            return super.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            requireStillWanted();
            return super.read(buffer, offset, length);
        }

        private void requireStillWanted() {
            Instant now = clock.instant();
            if (!now.isBefore(deadline)) {
                throw ImportUploadException.incomplete();
            }
            if (now.isBefore(nextCheck)) {
                return;
            }
            if (!intake.keepReceiving(jobId)) {
                throw ImportUploadException.notAccepted();
            }
            nextCheck = now.plus(ACTIVE_CHECK_INTERVAL);
        }
    }
}
