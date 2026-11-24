package gg.modl.backend.hostedimport.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ImportTallyTest {
    @Test
    void confirmingKeepsTotalsAndAnalysisOnlyIssuesSoApplyDoesNotCountTwice() {
        IssueLog analysis = new IssueLog();
        analysis.seen(ImportSubject.PUNISHMENTS, 10);
        analysis.report(IssueCode.PUNISHMENT_ROLLBACK_ENDS, "p1");
        analysis.report(IssueCode.COLLECTION_EXCLUDED, "sessions");
        analysis.report(IssueCode.FILE_KEY_RENAMED, "k");

        ImportTally carried = analysis.tally().carriedIntoApply();

        assertThat(carried.count(ImportSubject.PUNISHMENTS)).isEqualTo(new SubjectCount(10, 0, 0, 0));
        assertThat(carried.issues()).containsKeys(IssueCode.COLLECTION_EXCLUDED, IssueCode.FILE_KEY_RENAMED)
            .doesNotContainKey(IssueCode.PUNISHMENT_ROLLBACK_ENDS);

        IssueLog apply = new IssueLog();
        apply.report(IssueCode.PUNISHMENT_ROLLBACK_ENDS, "p1");
        apply.imported(ImportSubject.PUNISHMENTS);
        ImportTally finished = carried.plus(apply.tally());

        assertThat(finished.issues().get(IssueCode.PUNISHMENT_ROLLBACK_ENDS).count()).isEqualTo(1);
        assertThat(finished.count(ImportSubject.PUNISHMENTS)).isEqualTo(new SubjectCount(10, 1, 0, 0));
    }

    @Test
    void samplesAreBoundedAndDistinct() {
        IssueLog log = new IssueLog();
        for (int i = 0; i < 20; i++) {
            log.report(IssueCode.ID_REMAPPED, "id-" + (i % 7));
        }
        IssueEntry entry = log.tally().issues().get(IssueCode.ID_REMAPPED);
        assertThat(entry.count()).isEqualTo(20);
        assertThat(entry.samples()).hasSize(IssueEntry.MAX_SAMPLES).doesNotHaveDuplicates();
    }

    @Test
    void onlyPausedImportsBeforeAccessCanBeAbandonedAndDiscardStopsAtConfirm() {
        ImportJob paused = ImportJob.started("a", ImportSource.UPLOAD, null, 1, null, Instant.EPOCH)
            .withStatus(ImportStatus.PAUSED).withPhase(ImportPhase.PLAYERS).withReason(ImportReason.DISK_SPACE);
        assertThat(paused.canAbandon()).isTrue();
        assertThat(paused.canResume()).isTrue();
        assertThat(paused.canDiscard()).isFalse();
        assertThat(paused.withPhase(ImportPhase.ACCESS).canAbandon()).isFalse();
        assertThat(paused.withReason(ImportReason.ARCHIVE_MISSING).canResume()).isFalse();
        assertThat(paused.withStatus(ImportStatus.REVIEW).canDiscard()).isTrue();
    }

    @Test
    void fromAccessOnAPausedImportCannotBeAbandonedButCanBeFinishedWhateverTheReason() {
        ImportJob paused = pausedIn(ImportPhase.SUBSCRIPTIONS).withReason(ImportReason.UNEXPECTED);

        assertThat(paused.canAbandon()).isFalse();
        assertThat(paused.canFinish()).isTrue();
        assertThat(paused.withReason(ImportReason.ARCHIVE_MISSING).canFinish()).isTrue();
        assertThat(paused.withReason(ImportReason.ARCHIVE_MISSING).canAbandon()).isFalse();
        assertThat(paused.withPhase(ImportPhase.ACCESS).canFinish()).isTrue();
        assertThat(paused.withPhase(ImportPhase.FINISH).canFinish()).isTrue();
        assertThat(paused.withPhase(ImportPhase.AUDIT_LOGS).canFinish()).isFalse();
        assertThat(paused.withStatus(ImportStatus.APPLYING).canFinish()).isFalse();
    }

    @Test
    void anImportThatKeepsFailingInSubscriptionsFinishesWithAnIssueAndNoLongerBlocksTheWizard() {
        ImportJob paused = pausedIn(ImportPhase.SUBSCRIPTIONS).withReason(ImportReason.UNEXPECTED)
            .withPhaseDone(2).withPhaseTotal(5);
        Instant now = Instant.parse("2026-11-22T12:00:00Z");

        ImportJob finished = paused.finishedEarly(now);

        assertThat(finished.status()).isEqualTo(ImportStatus.COMPLETED);
        assertThat(finished.status().isInProgress()).isFalse();
        assertThat(finished.finishedAt()).isEqualTo(now);
        assertThat(finished.tally().issues()).containsOnlyKeys(IssueCode.SUBSCRIPTIONS_NOT_RESTORED);
    }

    @Test
    void finishingBeforeAccessCommittedNamesEveryStepThatDidNotRun() {
        ImportJob finished = pausedIn(ImportPhase.ACCESS).withPhaseDone(0).withPhaseTotal(1).finishedEarly(Instant.EPOCH);

        assertThat(finished.tally().issues()).containsOnlyKeys(IssueCode.STAFF_ACCESS_NOT_RESTORED, IssueCode.SUBSCRIPTIONS_NOT_RESTORED);
    }

    @Test
    void finishingAfterAccessCommittedReportsOnlyTheStepsStillAhead() {
        ImportJob afterAccess = pausedIn(ImportPhase.ACCESS).withPhaseDone(1).withPhaseTotal(1).finishedEarly(Instant.EPOCH);
        ImportJob atFinish = pausedIn(ImportPhase.FINISH).withPhaseDone(0).withPhaseTotal(1).finishedEarly(Instant.EPOCH);

        assertThat(afterAccess.tally().issues()).containsOnlyKeys(IssueCode.SUBSCRIPTIONS_NOT_RESTORED);
        assertThat(atFinish.tally().issues()).isEmpty();
    }

    @Test
    void finishingKeepsWhatTheImportAlreadyCounted() {
        IssueLog applied = new IssueLog();
        applied.imported(ImportSubject.PLAYERS);
        ImportJob finished = pausedIn(ImportPhase.SUBSCRIPTIONS).withTally(applied.tally()).finishedEarly(Instant.EPOCH);

        assertThat(finished.tally().count(ImportSubject.PLAYERS).imported()).isEqualTo(1);
    }

    private static ImportJob pausedIn(ImportPhase phase) {
        return ImportJob.started("a", ImportSource.UPLOAD, null, 1, null, Instant.EPOCH)
            .withStatus(ImportStatus.PAUSED).withPhase(phase).withReason(ImportReason.DISK_SPACE);
    }
}
