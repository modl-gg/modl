package gg.modl.backend.update.commit;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.log.service.AuditActor;
import gg.modl.backend.log.service.LogService;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.guard.StartupDeadline;
import gg.modl.backend.update.guard.UpdateOutcomes;
import gg.modl.backend.update.install.UpdateHosts;
import gg.modl.backend.update.state.InstanceStateRepository;
import gg.modl.update.UpdateFiles;
import gg.modl.update.UpdatePhase;
import gg.modl.update.UpdateTransaction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class UpdateCommit {
    static final Duration PROBE_BUDGET = Duration.ofSeconds(60);
    static final Duration PROBE_PAUSE = Duration.ofSeconds(1);

    private final UpdateGate gate;
    private final UpdateFiles files;
    private final InstanceStateRepository repository;
    private final UnitOfWork unitOfWork;
    private final StartupDeadline deadline;
    private final UpdateOutcomes outcomes;
    private final LogService logService;
    private final InstanceContext instance;
    private final OperatorNotice notice;
    private final ReleaseBuild build;
    private final Clock clock;
    private final UpdateHosts hosts;
    private final ApplicationEventPublisher events;

    public UpdateCommit(UpdateGate gate, UpdateFiles files, InstanceStateRepository repository, UnitOfWork unitOfWork,
                        StartupDeadline deadline, UpdateOutcomes outcomes, LogService logService, InstanceContext instance,
                        OperatorNotice notice, ReleaseBuild build, Clock clock, UpdateHosts hosts, ApplicationEventPublisher events) {
        this.gate = gate;
        this.files = files;
        this.repository = repository;
        this.unitOfWork = unitOfWork;
        this.deadline = deadline;
        this.outcomes = outcomes;
        this.logService = logService;
        this.instance = instance;
        this.notice = notice;
        this.build = build;
        this.clock = clock;
        this.hosts = hosts;
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void commit() {
        outcomes.takeRolledBack().ifPresent(rolledBack -> logService.recordStaffAction(instance.instance(), AuditActor.system(),
            "Rolled back the update from modl " + rolledBack.transaction().from() + " to " + rolledBack.transaction().to()
                + (rolledBack.restored() ? "; the database was restored from the pre-update backup" : "")));
        gate.holding().ifPresent(this::commit);
        gate.markInstanceCommitted();
        events.publishEvent(new InstanceCommittedEvent());
    }

    private void commit(UpdateTransaction transaction) {
        requireStillPending(transaction);
        probeWritable();
        try {
            files.writeCommitted(hosts.committed(transaction, clock.instant()));
            files.deletePending();
        } catch (IOException e) {
            throw new UncheckedIOException("The finished update could not be recorded in " + files.root(), e);
        }
        deadline.cancel();
        gate.open();
        logService.recordStaffAction(instance.instance(), AuditActor.system(), "Updated modl from " + transaction.from() + " to " + transaction.to());
        notice.publish("Updated modl from {} to {}", transaction.from(), transaction.to());
        removeLeftovers();
    }

    private void requireStillPending(UpdateTransaction transaction) {
        UpdateTransaction current;
        try {
            current = files.readPending();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (current == null || !current.id().equals(transaction.id()) || current.phase() != UpdatePhase.STARTING) {
            throw new IllegalStateException("updates/pending.json changed while modl " + build.version() + " was starting");
        }
    }

    private void probeWritable() {
        long deadlineNanos = System.nanoTime() + PROBE_BUDGET.toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadlineNanos) {
            try {
                if (Boolean.TRUE.equals(unitOfWork.write(() -> repository.confirmRunningVersion(build.versionText(), clock.instant())))) {
                    return;
                }
                last = new IllegalStateException("The database does not record modl " + build.version() + " as running");
            } catch (RuntimeException e) {
                last = e;
            }
            pause();
        }
        throw new IllegalStateException("The database did not accept a write within " + PROBE_BUDGET.toSeconds() + " seconds", last);
    }

    private void removeLeftovers() {
        files.deleteVersionsExcept(Set.of(build.version()));
        try {
            files.deleteLastFailure();
        } catch (IOException e) {
            log.debug("Could not remove the previous update failure", e);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(PROBE_PAUSE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while finishing the update", e);
        }
    }
}
