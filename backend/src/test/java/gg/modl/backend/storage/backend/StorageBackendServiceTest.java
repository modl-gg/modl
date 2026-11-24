package gg.modl.backend.storage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.migration.service.ImportKind;
import gg.modl.backend.migration.service.TestImports;
import gg.modl.backend.migration.service.ImportExclusion;
import gg.modl.backend.storage.StorageProperties;
import gg.modl.backend.storage.StorageType;
import gg.modl.backend.storage.repository.StorageReservationRepository;
import gg.modl.backend.storage.s3.S3ConnectionResult;
import gg.modl.backend.storage.s3.S3ConnectionTester;
import gg.modl.backend.storage.service.StorageOwnerMarker;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageBackendServiceTest {
    private static final String OWNER = "owner@example.com";
    private static final StorageProperties.S3 BUCKET = new StorageProperties.S3("https://s3.invalid", "eu-1", "modl", "AKID",
        "secret", null);

    @TempDir
    Path dataDirectory;
    @TempDir
    Path storageRoot;

    private InstanceTestKit kit;
    private StorageBackends backends;
    private StorageBackendSettingsStore settings;
    private InMemoryStorageTransfers transfers;
    private StorageOwnerMarker ownerMarker;
    private final AtomicBoolean importRunning = new AtomicBoolean();
    private final AtomicBoolean databaseMoving = new AtomicBoolean();
    private StorageBackendService service;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
        backends = new StorageBackends(StorageTestBackends.local(storageRoot), Clock.systemUTC());
        settings = new StorageBackendSettingsStore(kit.documents(), kit.unitOfWork());
        transfers = new InMemoryStorageTransfers();
        ownerMarker = mock(StorageOwnerMarker.class);
        when(ownerMarker.claim(any())).thenReturn(new StorageOwnerMarker.Verdict(true, false, null));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (service != null) {
            service.stop();
        }
        backends.close();
        kit.close();
    }

    @Test
    void aMoveIsRefusedWhileAnImportWritesFiles() {
        settings.update(stored -> stored.withS3(BUCKET).migrating(StorageType.S3, StorageType.LOCAL).migrated(null));
        service = service();
        importRunning.set(true);

        assertThatThrownBy(() -> service.switchTo(kit.server(), OWNER, StorageType.S3))
            .isInstanceOfSatisfying(ConflictException.class, refused ->
                assertThat(refused.getMessage()).contains("import"));
        assertThat(settings.current().migrationTarget()).isEmpty();
        assertThat(transfers.latest()).isEmpty();
    }

    @Test
    void storageChangesAreRefusedWhileTheDatabaseMoves() {
        settings.update(stored -> stored.withS3(BUCKET).migrating(StorageType.S3, StorageType.LOCAL).migrated(null));
        service = service();
        databaseMoving.set(true);

        assertThatThrownBy(() -> service.switchTo(kit.server(), OWNER, StorageType.S3))
            .isInstanceOfSatisfying(ConflictException.class, refused ->
                assertThat(refused.getMachineCode()).isEqualTo(StorageBackendService.DATABASE_MOVING_CODE));
        assertThatThrownBy(() -> service.retry(kit.server(), OWNER))
            .isInstanceOfSatisfying(ConflictException.class, refused ->
                assertThat(refused.getMachineCode()).isEqualTo(StorageBackendService.DATABASE_MOVING_CODE));
        assertThat(settings.current().migrationTarget()).isEmpty();
        assertThat(transfers.latest()).isEmpty();
    }

    @Test
    void aCopyLeftRunningWithoutAJobCanBeRetried() {
        settings.update(stored -> stored.withS3(BUCKET).migrating(StorageType.LOCAL, StorageType.S3));
        transfers.insert(StorageTransfer.started("t-1", StorageType.LOCAL, StorageType.S3, Instant.EPOCH));
        service = service();

        StorageBackendView view = service.retry(kit.server(), OWNER);

        assertThat(view.latestTransfer()).hasValueSatisfying(transfer -> assertThat(transfer.id()).isEqualTo("t-1"));
    }

    @Test
    void duringAMoveThePageShowsWhereNewFilesGo() {
        settings.update(stored -> stored.withS3(BUCKET).migrating(StorageType.LOCAL, StorageType.S3));
        service = service();

        assertThat(service.view().active()).isEqualTo(StorageType.S3);
    }

    @Test
    void oldCopiesCannotBeRemovedWhileFilesAreMoving() {
        settings.update(stored -> stored.withS3(BUCKET).migrating(StorageType.S3, StorageType.LOCAL).migrated(null)
            .migrating(StorageType.LOCAL, StorageType.S3));
        service = service();

        assertThatThrownBy(() -> service.removeOldCopies(kit.server(), OWNER)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aStoredSecretIsNotSentToAnotherEndpoint() {
        settings.update(stored -> stored.withS3(BUCKET));
        S3ConnectionTester tester = mock(S3ConnectionTester.class);
        service = service(tester);
        StorageProperties.S3 elsewhere = new StorageProperties.S3("https://attacker.invalid", "eu-1", "modl", "AKID", null, null);

        service.test(elsewhere);

        verify(tester).test(elsewhere);
    }

    private StorageBackendService service() {
        S3ConnectionTester tester = mock(S3ConnectionTester.class);
        when(tester.test(any())).thenReturn(new S3ConnectionResult(true, S3ConnectionResult.OK, ""));
        return service(tester);
    }

    private StorageBackendService service(S3ConnectionTester tester) {
        StorageProperties environment = new StorageProperties(null, null);
        SwitchableObjectStorage storage = new SwitchableObjectStorage(StorageRoutes.initial(environment, settings.current(), backends));
        return new StorageBackendService(environment, settings, backends, storage, transfers, mock(StorageReservationRepository.class),
            tester, ownerMarker, new OldCopyRemoval(ownerMarker),
            TestImports.running(() -> List.of(TestImports.of(ImportKind.HOSTED, importRunning::get))), new ImportExclusion(), databaseMoving::get, kit.unitOfWork(), mock(PanelActionAuditor.class),
            mock(OperatorNotice.class), Clock.systemUTC());
    }
}
