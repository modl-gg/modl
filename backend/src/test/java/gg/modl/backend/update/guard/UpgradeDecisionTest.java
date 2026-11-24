package gg.modl.backend.update.guard;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.update.guard.UpgradeDecision.Action;
import gg.modl.backend.update.guard.UpgradeDecision.Proceed;
import gg.modl.backend.update.guard.UpgradeDecision.Refuse;
import gg.modl.backend.update.guard.UpgradeDecision.Resume;
import gg.modl.backend.update.guard.UpgradeDecision.RollBack;
import gg.modl.backend.update.guard.UpgradeDecision.Upgrade;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.TransactionKind;
import gg.modl.update.UpdatePhase;
import gg.modl.update.UpdateTransaction;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class UpgradeDecisionTest {
    private static final String DATABASE = "database-1";
    private static final String OLD = "3.0.0";
    private static final String NEW = "3.0.1";
    private static final String NEWEST = "3.0.2";
    private static final Instant NOW = Instant.parse("2026-12-01T12:00:00Z");

    @Test
    void anotherDatabaseIsRefused() {
        UpdateTransaction pending = pending(UpdatePhase.MIGRATING).databaseId("database-2").build();

        assertThat(decide(NEW, pending, true, OLD)).isInstanceOfSatisfying(Refuse.class,
            refuse -> assertThat(refuse.message()).contains("pending.json"));
    }

    @ParameterizedTest
    @EnumSource(names = {"STAGED", "BACKING_UP"})
    void theOldVersionRollsBackWithoutRestoringBeforeMigration(UpdatePhase phase) {
        assertThat(decide(OLD, pending(phase).build(), true, OLD))
            .isInstanceOfSatisfying(RollBack.class, rollBack -> assertThat(rollBack.restore()).isFalse());
    }

    @ParameterizedTest
    @EnumSource(names = {"MIGRATING", "STARTING", "ROLLBACK"})
    void theOldVersionRestoresOnceTheDatabaseMayHaveChanged(UpdatePhase phase) {
        assertThat(decide(OLD, backedUp(phase), true, NEW))
            .isInstanceOfSatisfying(RollBack.class, rollBack -> assertThat(rollBack.restore()).isTrue());
    }

    @Test
    void theOldVersionRollsBackAWipedDatabaseInsteadOfInstallingFresh() {
        assertThat(decide(OLD, backedUp(UpdatePhase.ROLLBACK), false, null))
            .isInstanceOfSatisfying(RollBack.class, rollBack -> assertThat(rollBack.restore()).isTrue());
    }

    @Test
    void theOldVersionWithoutBackupOnlyReturnsWhenTheSchemaDidNotChange() {
        assertThat(decide(OLD, pending(UpdatePhase.STARTING).schemaChanged(false).build(), true, NEW))
            .isInstanceOfSatisfying(RollBack.class, rollBack -> assertThat(rollBack.restore()).isFalse());
        assertThat(decide(OLD, pending(UpdatePhase.STARTING).schemaChanged(true).build(), true, NEW)).isInstanceOf(Refuse.class);
        assertThat(decide(OLD, pending(UpdatePhase.MIGRATING).build(), true, OLD)).isInstanceOf(Refuse.class);
    }

    @ParameterizedTest
    @EnumSource(names = {"STAGED", "BACKING_UP"})
    void aRollbackFromBeforeTheMigrationNeedsNoBackup(UpdatePhase phase) {
        assertThat(decide(OLD, pending(phase).build().rollingBack(), true, OLD))
            .isInstanceOfSatisfying(RollBack.class, rollBack -> assertThat(rollBack.restore()).isFalse());
    }

    @ParameterizedTest
    @EnumSource(names = {"STAGED", "BACKING_UP"})
    void theNewVersionBacksUpBeforeItMigrates(UpdatePhase phase) {
        assertThat(decide(NEW, pending(phase).build(), true, OLD)).isInstanceOfSatisfying(Upgrade.class, upgrade -> {
            assertThat(upgrade.backUp()).isTrue();
            assertThat(upgrade.restoreFirst()).isFalse();
        });
    }

    @Test
    void theMinimumVersionAlsoAppliesToAStagedUpdate() {
        UpdateTransaction pending = UpdateTransaction.builder("pending", TransactionKind.JAR, version("2.9.0"), version(NEW), NOW).build();

        assertThat(decide(NEW, pending, true, "2.9.0")).isInstanceOf(Refuse.class);
    }

    @ParameterizedTest
    @EnumSource(names = {"MIGRATING", "ROLLBACK"})
    void anInterruptedMigrationReusesItsBackupAndNeverTakesAnother(UpdatePhase phase) {
        assertThat(decide(NEW, backedUp(phase), true, OLD)).isInstanceOfSatisfying(Upgrade.class, upgrade -> {
            assertThat(upgrade.backUp()).isFalse();
            assertThat(upgrade.restoreFirst()).isTrue();
            assertThat(upgrade.transaction().backup()).isEqualTo("pre-update-1.zip");
        });
    }

    @Test
    void anInterruptedMigrationWithoutBackupIsNotBackedUpEither() {
        assertThat(decide(NEW, pending(UpdatePhase.MIGRATING).build(), true, OLD)).isInstanceOfSatisfying(Upgrade.class, upgrade -> {
            assertThat(upgrade.backUp()).isFalse();
            assertThat(upgrade.restoreFirst()).isFalse();
        });
    }

    @Test
    void aStartThatFinishedMigratingResumes() {
        assertThat(decide(NEW, backedUp(UpdatePhase.STARTING), true, NEW)).isInstanceOf(Resume.class);
    }

    @Test
    void aNewerVersionRestoresAndReplacesTheTransactionWithoutEverLookingUntouched() {
        assertThat(decide(NEWEST, backedUp(UpdatePhase.STARTING), true, NEW)).isInstanceOfSatisfying(Upgrade.class, upgrade -> {
            assertThat(upgrade.restoreFirst()).isTrue();
            assertThat(upgrade.backUp()).isFalse();
            assertThat(upgrade.transaction().phase()).isEqualTo(UpdatePhase.MIGRATING);
            assertThat(upgrade.transaction().from()).isEqualTo(version(OLD));
            assertThat(upgrade.transaction().to()).isEqualTo(version(NEWEST));
            assertThat(upgrade.transaction().managed()).isFalse();
            assertThat(upgrade.transaction().backup()).isEqualTo("pre-update-1.zip");
        });
    }

    @Test
    void aNewerVersionBacksUpADatabaseTheUpdateNeverTouched() {
        assertThat(decide(NEWEST, pending(UpdatePhase.STAGED).build(), true, OLD)).isInstanceOfSatisfying(Upgrade.class, upgrade -> {
            assertThat(upgrade.restoreFirst()).isFalse();
            assertThat(upgrade.backUp()).isTrue();
        });
    }

    @Test
    void anyOtherVersionIsRefusedWithBothUsableVersions() {
        assertThat(decide("2.9.9", backedUp(UpdatePhase.STARTING), true, NEW)).isInstanceOfSatisfying(Refuse.class,
            refuse -> assertThat(refuse.message()).contains(OLD, NEW));
    }

    @Test
    void aDatabaseWithoutModlTablesIsAFreshInstall() {
        assertThat(decide(NEW, null, false, null)).isEqualTo(new Proceed(true));
    }

    @Test
    void theRecordedVersionStartsNormally() {
        assertThat(decide(NEW, null, true, NEW)).isEqualTo(new Proceed(false));
    }

    @Test
    void aManualUpgradeIsAnUnmanagedTransactionWithABackup() {
        assertThat(decide(NEW, null, true, OLD)).isInstanceOfSatisfying(Upgrade.class, upgrade -> {
            assertThat(upgrade.backUp()).isTrue();
            assertThat(upgrade.transaction().managed()).isFalse();
            assertThat(upgrade.transaction().kind()).isEqualTo(TransactionKind.NONE);
            assertThat(upgrade.transaction().databaseId()).isEqualTo(DATABASE);
        });
    }

    @Test
    void aTooOldDatabaseIsToldWhatToInstallFirst() {
        assertThat(decide(NEW, null, true, "2.9.0")).isInstanceOfSatisfying(Refuse.class,
            refuse -> assertThat(refuse.message()).contains("Install modl 3.0.0 first"));
    }

    @Test
    void anUnversionedDevelopmentDatabaseSkipsTheMinimum() {
        assertThat(decide(NEW, null, true, null)).isInstanceOfSatisfying(Upgrade.class,
            upgrade -> assertThat(upgrade.transaction().from()).isEqualTo(version("0.0.0")));
    }

    @Test
    void aDowngradeNeedsTheSwitchAndAKnownSchema() {
        assertThat(decide(OLD, null, true, NEW)).isInstanceOf(Refuse.class);
        assertThat(downgrade(false)).isEqualTo(new Proceed(false));
        assertThat(downgrade(true)).isInstanceOf(Refuse.class);
    }

    @Test
    void anUnreadableRecordedVersionFailsClosed() {
        assertThat(decide(NEW, null, true, "dev")).isInstanceOf(Refuse.class);
    }

    private static Action decide(String current, @Nullable UpdateTransaction pending, boolean tables, @Nullable String lastRun) {
        return UpgradeDecision.decide(new UpgradeDecision.Inputs(version(current), version(OLD), pending, tables, lastRun, DATABASE,
            false, false, "created", NOW));
    }

    private static Action downgrade(boolean schemaAboveBinary) {
        return UpgradeDecision.decide(new UpgradeDecision.Inputs(version(OLD), version(OLD), null, true, NEW, DATABASE,
            schemaAboveBinary, true, "created", NOW));
    }

    private static UpdateTransaction.Builder pending(UpdatePhase phase) {
        return UpdateTransaction.builder("pending", TransactionKind.JAR, version(OLD), version(NEW), NOW).managed(true).phase(phase)
            .databaseId(DATABASE);
    }

    private static UpdateTransaction backedUp(UpdatePhase phase) {
        return pending(phase).backup("pre-update-1.zip", "a".repeat(64)).schemaChanged(true).build();
    }

    private static ReleaseVersion version(String text) {
        return ReleaseVersion.parse(text);
    }
}
