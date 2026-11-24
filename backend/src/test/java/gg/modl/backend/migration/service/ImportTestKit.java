package gg.modl.backend.migration.service;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static gg.modl.backend.database.jooq.Tables.IMPORT_JOBS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;
import static org.mockito.Mockito.mock;

import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.infrastructure.disk.DiskSpaceFloor;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.log.repository.JooqAuditLogRepository;
import gg.modl.backend.log.service.LogService;
import gg.modl.backend.migration.config.MigrationConfiguration;
import gg.modl.backend.migration.data.ExportCounts;
import gg.modl.backend.migration.data.ImportEnding;
import gg.modl.backend.migration.data.ImportOrigin;
import gg.modl.backend.migration.data.ImportState;
import gg.modl.backend.migration.data.ImportedCounts;
import gg.modl.backend.migration.data.IpHistoryRetention;
import gg.modl.backend.migration.data.MigrationStatus;
import gg.modl.backend.migration.data.ScopedPunishments;
import gg.modl.backend.migration.repository.JooqImportJobRepository;
import gg.modl.backend.player.config.IpHistoryPolicy;
import gg.modl.backend.player.repository.ImportedAddressRetention;
import gg.modl.backend.player.repository.JooqRetainedIpHistoryRepository;
import gg.modl.backend.player.repository.PlayerFixtures;
import gg.modl.backend.player.repository.PlayerRepositories;
import gg.modl.backend.player.repository.RetainedIpHistoryRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

final class ImportTestKit implements AutoCloseable {
    static final ImportOrigin ORIGIN = new ImportOrigin("Proxy", "198.51.100.7");
    static final long MAX_BYTES = 1024;

    private final InstanceTestKit instance;
    private final Path uploadDirectory;
    private final Server readyServer = TestServers.server("server", "admin@example.com");
    private final JooqImportJobRepository jobs;
    private final RetainedIpHistoryRepository retained;
    private final ImportUploadFiles files;
    private final MigrationProcessor worker = mock(MigrationProcessor.class);
    private final MigrationService service;
    private final RetainedIpHistoryRelease release;
    private volatile boolean movingFiles;
    private volatile boolean movingDatabase;
    private final ImportProcessingJobs processing;
    private final ImportWorkerLauncher launcher;
    private final ImportJobFileIntake intake;

    private ImportTestKit(InstanceTestKit instance, Path uploadDirectory) {
        this.instance = instance;
        this.uploadDirectory = uploadDirectory;
        SqlTestDatabase database = instance.database();
        this.jobs = new JooqImportJobRepository(database.dsl());
        this.retained = new JooqRetainedIpHistoryRepository(database.dsl(), database.unitOfWork());
        this.files = new ImportUploadFiles(uploadDirectory);
        LogService log = new LogService(new JooqAuditLogRepository(database.dsl(), database.unitOfWork(), database.sequencer(),
            database.jsonColumns()), database.unitOfWork(), database.clock());
        this.release = new RetainedIpHistoryRelease(jobs, retained);
        this.service = new MigrationService(jobs, release, IpHistoryPolicy.ofDays(365), new MigrationConfiguration(), log,
            database.unitOfWork(), database.clock(), new ImportExclusion(), TestImports.none(), () -> movingFiles,
            () -> movingDatabase);
        this.processing = new ImportProcessingJobs(jobs, retained, new RetainedIpHistoryReleaseWorker(release), log,
            database.unitOfWork(), database.clock());
        this.launcher = new ImportWorkerLauncher(instance.instanceContext(), worker, processing);
        this.intake = new ImportJobFileIntake(jobs, files, launcher, database.unitOfWork(), database.clock());
    }

    static ImportTestKit start(DialectTarget target, Path directory, WriteAttemptHook hook) {
        InstanceTestKit instance = InstanceTestKit.start(target, directory.resolve("data"), hook);
        return new ImportTestKit(instance, directory.resolve("uploads"));
    }

    InstanceTestKit instance() {
        return instance;
    }

    MutableClock clock() {
        return instance.clock();
    }

    Server readyServer() {
        return readyServer;
    }

    JooqImportJobRepository jobs() {
        return jobs;
    }

    RetainedIpHistoryRepository retained() {
        return retained;
    }

    void storageMoving(boolean moving) {
        movingFiles = moving;
    }

    void databaseMoving(boolean moving) {
        movingDatabase = moving;
    }

    RetainedIpHistoryRelease release() {
        return release;
    }

    void retainAddressSeenAt(Instant seenAt) {
        SqlTestDatabase database = instance.database();
        String playerId = PlayerFixtures.login(PlayerRepositories.on(database).players(), "Imported", "198.51.100.9", seenAt);
        database.unitOfWork().write(() -> database.dsl().update(PLAYER_IPS)
            .set(PLAYER_IPS.RETAINED_UNTIL_AT, ImportedAddressRetention.KEPT.retainedUntil())
            .where(PLAYER_IPS.PLAYER_ID.eq(playerId))
            .execute());
    }

    ImportUploadFiles files() {
        return files;
    }

    Path uploadDirectory() {
        return uploadDirectory;
    }

    MigrationProcessor worker() {
        return worker;
    }

    MigrationService service() {
        return service;
    }

    ImportProcessingJobs processing() {
        return processing;
    }

    ImportWorkerLauncher launcher() {
        return launcher;
    }

    ImportJobFileIntake intake() {
        return intake;
    }

    ImportUploadReceiver receiver(ImportFileIntake receiving) {
        return new ImportUploadReceiver(receiving, files, new DiskSpaceFloor(0, 0), MAX_BYTES, clock());
    }

    ImportJobRecovery recovery() {
        return new ImportJobRecovery(jobs, files, launcher, instance.unitOfWork(), clock());
    }

    String startJob() {
        return startJob(IpHistoryRetention.STANDARD);
    }

    String startJob(IpHistoryRetention retention) {
        return service.start(readyServer, "litebans", ORIGIN, ScopedPunishments.SKIP, retention).getId();
    }

    String startJobIn(ImportState state) {
        String jobId = startJob();
        if (state == ImportState.BUILDING) {
            return jobId;
        }
        service.reportExport(jobId, true, new ExportCounts(3, 4, 1, 2));
        if (state == ImportState.RECEIVING || state == ImportState.PROCESSING || state == ImportState.COMPLETED) {
            intake.claim(jobId);
        }
        if (state == ImportState.PROCESSING || state == ImportState.COMPLETED) {
            instance.write(() -> jobs.beginProcessing(jobId, ImportState.RECEIVING, clock().instant()));
        }
        if (state == ImportState.COMPLETED) {
            instance.write(() -> jobs.complete(jobId, new ImportedCounts(3, 0, 4), clock().instant()));
        }
        if (state == ImportState.FAILED) {
            intake.refuse(jobId, ImportEnding.UPLOAD_FAILED);
        }
        return jobId;
    }

    void completeKeeping() {
        service.end(readyServer, jobs.findActive().orElseThrow().getId(), ImportEnding.CANCELLED, null, null);
        clock().advance(Duration.ofSeconds(1));
        String jobId = startJob(IpHistoryRetention.KEEP);
        service.reportExport(jobId, true, new ExportCounts(3, 4, 1, 2));
        intake.claim(jobId);
        instance.write(() -> jobs.beginProcessing(jobId, ImportState.RECEIVING, clock().instant()));
        processing.complete(readyServer, jobId, ImportedCounts.NONE);
    }

    MigrationStatus job(String jobId) {
        return jobs.findById(jobId).orElseThrow();
    }

    int storedJobs() {
        return instance.database().dsl().fetchCount(IMPORT_JOBS);
    }

    List<String> auditEntries() {
        return instance.database().dsl().select(AUDIT_LOGS.DESCRIPTION).from(AUDIT_LOGS).orderBy(AUDIT_LOGS.ID)
            .fetch(AUDIT_LOGS.DESCRIPTION);
    }

    Path storeUpload(String fileName) throws IOException {
        Files.createDirectories(uploadDirectory);
        return Files.writeString(uploadDirectory.resolve(fileName), "{}");
    }

    @Override
    public void close() {
        instance.close();
    }
}
