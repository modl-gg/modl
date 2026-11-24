package gg.modl.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.update.Selection.Target;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ArtifactSelectionTest {
    private static final ReleaseVersion V300 = ReleaseVersion.parse("3.0.0");
    private static final ReleaseVersion V301 = ReleaseVersion.parse("3.0.1");
    private static final ReleaseVersion V302 = ReleaseVersion.parse("3.0.2");
    private static final ReleaseVersion V310 = ReleaseVersion.parse("3.1.0");
    private static final String TO_ARTIFACT = "versions/3.0.1/modl-server-3.0.1.jar";
    private static final Instant NOW = Instant.parse("2026-12-01T12:00:00Z");
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void committedTransactionWinsOverPending() {
        UpdateTransaction pending = pending(V300, V301, UpdatePhase.STARTING).build();
        CommittedUpdate committed = CommittedUpdate.of(pending, NOW);

        Selection selection = select(V300, false, new UpdateState(null, committed, true));

        assertEquals(Target.COMMITTED, selection.target());
        assertEquals(TO_ARTIFACT, selection.artifact());
        assertTrue(selection.deletePending());
    }

    @Test
    void replacedBaseThatIsNewerOrEqualIsUsed() {
        UpdateTransaction pending = pending(V300, V301, UpdatePhase.STAGED).build();

        assertEquals(Target.BASE, select(V301, false, state(pending)).target());
        assertEquals(Target.BASE, select(V302, false, state(pending)).target());
    }

    @Test
    void embeddedNeverCrossesTheLine() {
        UpdateTransaction pending = UpdateTransaction.builder("t", TransactionKind.EMBEDDED, V300, V310, NOW).managed(true)
            .toArtifact("versions/3.1.0/modl-embedded-3.1.0.zip").build();

        Selection selection = ArtifactSelection.select(V300, TransactionKind.EMBEDDED, true, state(pending));

        assertEquals(Target.BASE, selection.target());
    }

    @Test
    void rollbackStartsTheFromArtifact() {
        UpdateTransaction pending = pending(V301, V302, UpdatePhase.ROLLBACK).fromArtifact("versions/3.0.1/modl-server-3.0.1.jar")
            .build();

        Selection selection = select(V300, false, state(pending));

        assertEquals(Target.ROLLBACK, selection.target());
        assertEquals(V301, selection.version());
        assertNull(selection.pendingToWrite());
    }

    @Test
    void rollbackWithoutFromArtifactNeedsTheBaseVersion() {
        UpdateTransaction pending = pending(V300, V301, UpdatePhase.ROLLBACK).build();

        Selection matching = select(V300, false, state(pending));
        assertEquals(Target.ROLLBACK, matching.target());
        assertTrue(matching.usesBase());

        UpdateTransaction foreign = pending(V301, V302, UpdatePhase.ROLLBACK).build();
        assertEquals(Target.REFUSED, select(V300, false, state(foreign)).target());
    }

    @Test
    void exhaustedAttemptsRollBackWhenAllowed() {
        UpdateTransaction pending = pending(V300, V301, UpdatePhase.STARTING).attempts(3).backup("pre-update-1.zip", "a".repeat(64))
            .build();

        Selection selection = select(V300, false, state(pending));

        assertEquals(Target.ROLLBACK, selection.target());
        assertEquals(UpdatePhase.ROLLBACK, selection.pendingToWrite().phase());
    }

    @Test
    void anUpdateThatNeverReachedMigrationRollsBackWithAnUntouchedDatabase() {
        for (UpdatePhase phase : new UpdatePhase[] {UpdatePhase.STAGED, UpdatePhase.BACKING_UP}) {
            Selection selection = select(V300, false, state(pending(V300, V301, phase).attempts(3).build()));

            assertEquals(Target.ROLLBACK, selection.target());
            assertEquals(false, selection.pendingToWrite().schemaChanged());
            assertTrue(selection.pendingToWrite().rollbackAllowed());
        }
        assertNull(pending(V300, V301, UpdatePhase.MIGRATING).build().rollingBack().schemaChanged());
    }

    @Test
    void exhaustedAttemptsWithoutRollbackKeepTrying() {
        UpdateTransaction pending = pending(V300, V301, UpdatePhase.STARTING).attempts(3).schemaChanged(true).build();

        Selection selection = select(V300, false, state(pending));

        assertEquals(Target.PENDING, selection.target());
        assertEquals(4, selection.pendingToWrite().attempts());
    }

    @Test
    void pendingTargetCountsTheAttemptAndIsSupervised() {
        Selection selection = select(V300, false, state(pending(V300, V301, UpdatePhase.STAGED).build()));

        assertEquals(Target.PENDING, selection.target());
        assertEquals(TO_ARTIFACT, selection.artifact());
        assertEquals(1, selection.pendingToWrite().attempts());
        assertTrue(selection.supervisedWithDeadline());
    }

    @Test
    void committedArtifactMustBeNewerAndOnTheLineWhenRequired() {
        CommittedUpdate committed = new CommittedUpdate("t", TransactionKind.JAR, V301, TO_ARTIFACT, NOW);
        assertEquals(Target.COMMITTED, select(V300, false, new UpdateState(null, committed, false)).target());
        assertEquals(Target.BASE, select(V301, false, new UpdateState(null, committed, false)).target());

        CommittedUpdate otherLine = new CommittedUpdate("t", TransactionKind.EMBEDDED, V310, "versions/3.1.0/modl-embedded-3.1.0.zip",
            NOW);
        assertEquals(Target.BASE, ArtifactSelection.select(V300, TransactionKind.EMBEDDED, true,
            new UpdateState(null, otherLine, false)).target());
    }

    @Test
    void unmanagedOrOtherKindPendingIsLeftToTheGuard() {
        UpdateTransaction manual = UpdateTransaction.builder("t", TransactionKind.NONE, V300, V301, NOW).build();
        Selection selection = select(V300, false, state(manual));

        assertEquals(Target.BASE, selection.target());
        assertFalse(selection.deletePending());
    }

    @Test
    void aCommandNeverRunsInAnUncommittedUpdateAndWritesNothing() {
        for (UpdatePhase phase : UpdatePhase.values()) {
            Selection selection = ArtifactSelection.forCommand(V300, TransactionKind.JAR, false,
                state(pending(V300, V301, phase).build()));

            assertEquals(Target.BASE, selection.target(), phase.name());
            assertNull(selection.pendingToWrite());
            assertFalse(selection.deletePending());
        }
    }

    @Test
    void aCommandRunsTheCommittedVersionWhileTheNextUpdateIsPending() {
        CommittedUpdate committed = new CommittedUpdate("earlier", TransactionKind.JAR, V301, TO_ARTIFACT, NOW);
        UpdateTransaction next = pending(V301, V302, UpdatePhase.MIGRATING).build();

        Selection selection = ArtifactSelection.forCommand(V300, TransactionKind.JAR, false, new UpdateState(next, committed, false));

        assertEquals(Target.COMMITTED, selection.target());
        assertEquals(TO_ARTIFACT, selection.artifact());
        assertNull(selection.pendingToWrite());
    }

    @Test
    void aTimedOutAttemptRollsBackOnTheNextStart() {
        UpdateTransaction attempt = pending(V300, V301, UpdatePhase.MIGRATING).attempts(1).backup("pre-update-1.zip", HASH).build();

        Selection selection = select(V300, false, state(attempt.timedOut("no progress for 15 minutes")));

        assertEquals(Target.ROLLBACK, selection.target());
        assertEquals(UpdatePhase.ROLLBACK, selection.pendingToWrite().phase());
        assertEquals("no progress for 15 minutes", selection.pendingToWrite().failure());
    }

    @Test
    void aTimedOutAttemptThatCannotRollBackIsTriedAgain() {
        UpdateTransaction attempt = pending(V300, V301, UpdatePhase.MIGRATING).attempts(1).schemaChanged(true).build();

        Selection selection = select(V300, false, state(attempt.timedOut("stalled")));

        assertEquals(Target.PENDING, selection.target());
    }

    private static Selection select(ReleaseVersion base, boolean sameLineOnly, UpdateState state) {
        return ArtifactSelection.select(base, TransactionKind.JAR, sameLineOnly, state);
    }

    private static UpdateState state(UpdateTransaction pending) {
        return new UpdateState(pending, null, false);
    }

    private static UpdateTransaction.Builder pending(ReleaseVersion from, ReleaseVersion to, UpdatePhase phase) {
        return UpdateTransaction.builder("transaction", TransactionKind.JAR, from, to, NOW).managed(true)
            .toArtifact("versions/" + to + "/modl-server-" + to + ".jar").phase(phase);
    }
}
