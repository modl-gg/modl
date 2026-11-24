package gg.modl.backend.update.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.update.Deployment;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.TransactionKind;
import gg.modl.update.UpdateTransaction;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class StartupDeadlineTest {
    private static final long MINUTE_NANOS = 60_000_000_000L;
    private static final UpdateTransaction UPDATE = UpdateTransaction.builder("t", TransactionKind.NONE, ReleaseVersion.parse("3.0.0"),
        ReleaseVersion.parse("3.0.1"), Instant.EPOCH).build();

    private final OperatorNotice notice = mock(OperatorNotice.class);
    private final AtomicLong now = new AtomicLong();

    @Test
    void anUnsupervisedStartPastTheLimitIsReportedOnceAndKeepsRunning() {
        StartupDeadline deadline = new StartupDeadline(notice, Deployment.DOCKER, false, now::get);
        deadline.begin();

        now.set(14 * MINUTE_NANOS);
        assertThat(deadline.reportIfLate(UPDATE)).isFalse();
        now.set(16 * MINUTE_NANOS);
        assertThat(deadline.reportIfLate(UPDATE)).isTrue();
        now.set(30 * MINUTE_NANOS);
        assertThat(deadline.reportIfLate(UPDATE)).isFalse();

        verify(notice, times(1)).publish(anyString(), any(Object[].class));
    }

    @Test
    void timeSpentOnTheBackupDoesNotCount() {
        StartupDeadline deadline = new StartupDeadline(notice, Deployment.JAR, false, now::get);
        deadline.begin();
        deadline.pause();
        now.set(40 * MINUTE_NANOS);
        deadline.resume();

        now.set(50 * MINUTE_NANOS);

        assertThat(deadline.reportIfLate(UPDATE)).isFalse();
    }

    @Test
    void supervisedStartsLeaveTheDeadlineToTheirSupervisor() {
        StartupDeadline embedded = new StartupDeadline(notice, Deployment.EMBEDDED, false, now::get);
        StartupDeadline launched = new StartupDeadline(notice, Deployment.JAR, true, now::get);

        embedded.enforce(UPDATE);
        launched.enforce(UPDATE);
        embedded.cancel();
        launched.cancel();

        verify(notice, never()).publish(anyString(), any(Object[].class));
    }
}
