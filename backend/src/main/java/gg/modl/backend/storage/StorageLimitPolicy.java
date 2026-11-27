package gg.modl.backend.storage;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.ratelimit.BucketPool;
import gg.modl.backend.infrastructure.ratelimit.BucketSpec;
import gg.modl.backend.infrastructure.util.ByteFormatUtil;
import gg.modl.backend.storage.repository.PrefixUsage;
import gg.modl.backend.storage.repository.StorageFileRepository;
import gg.modl.backend.storage.repository.StorageReservation;
import gg.modl.backend.storage.repository.StorageReservationRepository;
import io.github.bucket4j.Bucket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

@Component
public class StorageLimitPolicy {
    public static final Duration CONFIRMATION_GRACE = Duration.ofMinutes(15);
    static final int MAX_PUBLIC_FILES_PER_RECORD = 25;
    static final long MAX_PUBLIC_BYTES_PER_RECORD = 512L * 1024 * 1024;
    static final BucketSpec PUBLIC_CLIENT_BUDGET = new BucketSpec("PUBLIC_UPLOAD_MEGABYTES", 64, Duration.ofHours(6));
    private static final long BYTES_PER_MEGABYTE = 1024L * 1024;

    private static final String LOW_DISK_SPACE_MESSAGE = "The server is running low on disk space";

    private final UploadLimits uploadLimits;
    private final ConfirmedStorageTotal confirmedStorageTotal;
    private final StorageFileRepository storageFileRepository;
    private final StorageReservationRepository reservationRepository;
    private final ObjectStorage objectStorage;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final DataSize temporaryUploadCap;
    private final Duration temporaryUploadWindow;
    private final BucketPool bucketPool;
    private final ReentrantLock admissionLock = new ReentrantLock();

    public StorageLimitPolicy(
        UploadLimits uploadLimits,
        ConfirmedStorageTotal confirmedStorageTotal,
        StorageFileRepository storageFileRepository,
        StorageReservationRepository reservationRepository,
        ObjectStorage objectStorage,
        UnitOfWork unitOfWork,
        Clock clock,
        BucketPool bucketPool,
        @Value("${modl.storage.temp-upload.max-bytes:512MB}") DataSize temporaryUploadCap,
        @Value("${modl.storage.temp-upload.window:PT6H}") Duration temporaryUploadWindow
    ) {
        this.uploadLimits = uploadLimits;
        this.confirmedStorageTotal = confirmedStorageTotal;
        this.storageFileRepository = storageFileRepository;
        this.reservationRepository = reservationRepository;
        this.objectStorage = objectStorage;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.bucketPool = bucketPool;
        this.temporaryUploadCap = temporaryUploadCap;
        this.temporaryUploadWindow = temporaryUploadWindow;
    }

    public long maxUploadBytes(MediaCategory category) {
        return category.limitBytes(uploadLimits.maxUploadBytes());
    }

    public Instant uploadDeadline() {
        return clock.instant().plus(UploadTarget.VALIDITY);
    }

    public void cancel(String key) {
        reservationRepository.release(List.of(key));
    }

    public void reserve(String key, long bytes, Instant uploadDeadline) {
        admit(key, bytes, uploadDeadline, Admission.AUTHENTICATED);
    }

    public void reservePublic(String key, long bytes, Instant uploadDeadline, PublicUpload upload) {
        Bucket clientBudget = bucketPool.resolveBucket(PUBLIC_CLIENT_BUDGET, upload.clientNetwork());
        admit(key, bytes, uploadDeadline, new Admission() {
            @Override
            public UploadOrigin origin() {
                return UploadOrigin.ANONYMOUS;
            }

            @Override
            public void require(long requested) {
                requirePublicWindowBudget(requested);
                upload.record().ifPresent(prefix -> requireRecordBudget(prefix, requested));
                if (clientBudget.getAvailableTokens() < megabytes(requested)) {
                    throw new TemporaryUploadLimitExceededException();
                }
            }

            @Override
            public void charge(long requested) {
                clientBudget.tryConsume(megabytes(requested));
            }
        });
    }

    private void admit(String key, long bytes, Instant uploadDeadline, Admission admission) {
        long requested = Math.max(bytes, 0L);
        admissionLock.lock();
        try {
            admission.require(requested);
            ReservedBytes reserved = reservationRepository.sumReserved();
            requireTotalLimit(reserved.total() + requested);
            if (!objectStorage.hasCapacityFor(reserved.inFlight() + requested)) {
                throw new StorageLimitExceededException(LOW_DISK_SPACE_MESSAGE);
            }
            reservationRepository.reserve(new StorageReservation(
                key, requested, false, uploadDeadline.plus(CONFIRMATION_GRACE), clock.instant(), admission.origin()));
            admission.charge(requested);
        } finally {
            admissionLock.unlock();
        }
    }

    private void requireTotalLimit(long pendingBytes) {
        if (!uploadLimits.limitsTotalStorage()) {
            return;
        }
        long maxStorageBytes = uploadLimits.maxStorageBytes();
        if (confirmedStorageTotal.bytes() + pendingBytes > maxStorageBytes) {
            throw new StorageLimitExceededException("The storage limit of "
                + ByteFormatUtil.formatCompact(maxStorageBytes) + " has been reached");
        }
    }

    private void requirePublicWindowBudget(long requested) {
        Instant windowStart = clock.instant().minus(temporaryUploadWindow);
        long used = unitOfWork.read(() -> reservationRepository.sumReservedBytes(UploadOrigin.ANONYMOUS)
            + storageFileRepository.sumSizeSince(UploadOrigin.ANONYMOUS, windowStart));
        if (used + requested > temporaryUploadCap.toBytes()) {
            throw new TemporaryUploadLimitExceededException();
        }
    }

    private void requireRecordBudget(String recordPrefix, long requested) {
        PrefixUsage used = unitOfWork.read(() -> storageFileRepository.usageUnder(List.of(recordPrefix))
            .plus(reservationRepository.usageUnder(recordPrefix)));
        if (used.files() >= MAX_PUBLIC_FILES_PER_RECORD || used.bytes() + requested > MAX_PUBLIC_BYTES_PER_RECORD) {
            throw new TemporaryUploadLimitExceededException();
        }
    }

    private static long megabytes(long requested) {
        return Math.max(1L, (requested + BYTES_PER_MEGABYTE - 1) / BYTES_PER_MEGABYTE);
    }

    private interface Admission {
        Admission AUTHENTICATED = new Admission() {
            @Override
            public UploadOrigin origin() {
                return UploadOrigin.AUTHENTICATED;
            }

            @Override
            public void require(long requestedBytes) {
            }

            @Override
            public void charge(long requestedBytes) {
            }
        };

        UploadOrigin origin();

        void require(long requestedBytes);

        void charge(long requestedBytes);
    }
}
