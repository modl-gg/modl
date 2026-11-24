package gg.modl.backend.update.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.log.service.LogService;
import gg.modl.backend.update.JacksonJsonMaps;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.guard.StartupDeadline;
import gg.modl.backend.update.guard.UpdateOutcomes;
import gg.modl.backend.update.install.UpdateHosts;
import gg.modl.backend.update.state.InstanceStateRepository;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.TransactionKind;
import gg.modl.update.UpdateFiles;
import gg.modl.update.UpdatePhase;
import gg.modl.update.UpdateTransaction;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

class UpdateCommitTest {
    private static final ReleaseVersion FROM = ReleaseVersion.parse("3.0.0");
    private static final ReleaseVersion TO = ReleaseVersion.parse("3.0.1");

    @TempDir
    Path temp;

    private final UpdateGate gate = new UpdateGate();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final UpdateOutcomes outcomes = mock(UpdateOutcomes.class);

    @Test
    void aStartWithoutAnUpdateCommitsAtOnceAndReleasesTheStartupWork() {
        when(outcomes.takeRolledBack()).thenReturn(Optional.empty());

        commit(new UpdateFiles(temp, JacksonJsonMaps.INSTANCE)).commit();

        assertThat(gate.committed()).isTrue();
        verify(events).publishEvent(any(InstanceCommittedEvent.class));
    }

    @Test
    void aHeldUpdateThatCannotCommitNeverReleasesTheStartupWork() throws Exception {
        when(outcomes.takeRolledBack()).thenReturn(Optional.empty());
        UpdateFiles files = new UpdateFiles(temp, JacksonJsonMaps.INSTANCE);
        UpdateTransaction starting = UpdateTransaction.builder("held", TransactionKind.NONE, FROM, TO, Instant.EPOCH)
            .phase(UpdatePhase.STARTING).build();
        gate.hold(starting);
        files.writePending(starting.toBuilder().phase(UpdatePhase.ROLLBACK).build());

        assertThatThrownBy(() -> commit(files).commit()).isInstanceOf(IllegalStateException.class);

        assertThat(gate.committed()).isFalse();
        verify(events, never()).publishEvent(any(InstanceCommittedEvent.class));
    }

    private UpdateCommit commit(UpdateFiles files) {
        return new UpdateCommit(gate, files, mock(InstanceStateRepository.class), mock(UnitOfWork.class), mock(StartupDeadline.class),
            outcomes, mock(LogService.class), mock(InstanceContext.class), mock(OperatorNotice.class), ReleaseBuild.of(TO, FROM),
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), mock(UpdateHosts.class), events);
    }
}
