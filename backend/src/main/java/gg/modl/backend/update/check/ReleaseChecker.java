package gg.modl.backend.update.check;

import gg.modl.backend.database.DatabaseUnavailableException;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.update.DeploymentDetector;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.UpdateErrors;
import gg.modl.backend.update.commit.InstanceCommittedEvent;
import gg.modl.backend.update.state.CheckRecord;
import gg.modl.backend.update.state.InstanceState;
import gg.modl.backend.update.state.InstanceStateRepository;
import gg.modl.backend.update.state.ReleaseChannelState;
import gg.modl.update.SignedRelease;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ReleaseChecker {
    private final InstanceStateRepository repository;
    private final UnitOfWork unitOfWork;
    private final StableReleaseSource stableSource;
    private final PrereleaseSource prereleaseSource;
    private final CheckPolicy policy;
    private final UpdateNotices notices;
    private final DeploymentDetector deployment;
    private final ReleaseBuild build;
    private final Clock clock;
    private final CheckSchedule schedule;
    private @Nullable CompletableFuture<@Nullable Void> inFlight;
    private volatile boolean ready;

    public ReleaseChecker(InstanceStateRepository repository, UnitOfWork unitOfWork, StableReleaseSource stableSource,
                          PrereleaseSource prereleaseSource, CheckPolicy policy, UpdateNotices notices,
                          DeploymentDetector deployment, ReleaseBuild build, Clock clock) {
        this.repository = repository;
        this.unitOfWork = unitOfWork;
        this.stableSource = stableSource;
        this.prereleaseSource = prereleaseSource;
        this.policy = policy;
        this.notices = notices;
        this.deployment = deployment;
        this.build = build;
        this.clock = clock;
        this.schedule = new CheckSchedule(new Random());
    }

    @EventListener(InstanceCommittedEvent.class)
    public void scheduleFirstCheck() {
        if (!policy.checksAvailable()) {
            return;
        }
        try {
            unitOfWork.write(() -> repository.find()
                .ifPresent(state -> repository.scheduleNextCheck(schedule.firstCheck(clock.instant(), state.nextCheckAt()))));
            ready = true;
        } catch (DatabaseUnavailableException e) {
            log.debug("Skipped scheduling the first release check while the database is unavailable", e);
        }
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void tick() {
        if (!ready) {
            return;
        }
        try {
            Optional<InstanceState> state = unitOfWork.read(repository::find);
            if (state.isPresent() && policy.automaticChecks(state.get()) && schedule.due(state.get().nextCheckAt(), clock.instant())) {
                check();
            }
        } catch (DatabaseUnavailableException e) {
            log.debug("Skipped the release check while the database is unavailable", e);
        } catch (RuntimeException e) {
            log.warn("The release check failed", e);
        }
    }

    public void checkNow() {
        if (!policy.checksAvailable()) {
            throw new ConflictException("Update checks are turned off for this server", UpdateErrors.CHECK_DISABLED);
        }
        try {
            check();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime : e;
        }
    }

    private void check() {
        CompletableFuture<@Nullable Void> call;
        boolean owner;
        synchronized (this) {
            CompletableFuture<@Nullable Void> running = inFlight;
            owner = running == null;
            call = running == null ? new CompletableFuture<>() : running;
            inFlight = call;
        }
        if (!owner) {
            call.join();
            return;
        }
        try {
            runCheck();
            call.complete(null);
        } catch (RuntimeException e) {
            call.completeExceptionally(e);
            throw e;
        } finally {
            synchronized (this) {
                inFlight = null;
            }
        }
    }

    private void runCheck() {
        InstanceState before = unitOfWork.write(() -> {
            InstanceState state = repository.find().orElseThrow(() -> new IllegalStateException("The instance state row is missing"));
            repository.scheduleNextCheck(schedule.afterFailure(clock.instant(), state.checkFailures() + 1, null));
            return state;
        });
        SourceResult stable = stableSource.fetch(before.stable());
        SourceResult prerelease = before.includePrereleases() ? prereleaseSource.fetch(before.prerelease()) : null;
        CheckOutcome outcome = combined(stable, prerelease);
        int failures = outcome.isFailure() ? before.checkFailures() + 1 : 0;
        Instant now = clock.instant();
        Instant next = outcome.isFailure()
            ? schedule.afterFailure(now, failures, retryAfter(stable, prerelease))
            : schedule.afterSuccess(now);
        ReleaseChannelState prereleaseState = prerelease == null ? before.prerelease() : prerelease.state();
        Optional<SignedRelease> newest = OfferedRelease.highestNewer(build.version(), stable.release(),
            prerelease == null ? Optional.empty() : prerelease.release());
        unitOfWork.write(() -> {
            repository.recordCheck(CheckRecord.of(now, outcome.name(), failures, next, stable.state(), prereleaseState));
            newest.ifPresent(release -> {
                if (repository.markNotified(release.version().toString())) {
                    unitOfWork.afterCommit(() -> notices.releaseAvailable(release, build.version(), deployment.deployment()));
                }
            });
        });
    }

    private static CheckOutcome combined(SourceResult stable, @Nullable SourceResult prerelease) {
        if (stable.outcome().isFailure() || prerelease == null) {
            return stable.outcome();
        }
        if (prerelease.outcome().isFailure()) {
            return prerelease.outcome();
        }
        return stable.outcome() == CheckOutcome.NO_RELEASE && prerelease.release().isPresent()
            ? CheckOutcome.SUCCEEDED
            : stable.outcome();
    }

    @Nullable
    private static Duration retryAfter(SourceResult stable, @Nullable SourceResult prerelease) {
        Duration first = stable.retryAfter();
        Duration second = prerelease == null ? null : prerelease.retryAfter();
        if (first == null) {
            return second;
        }
        return second == null || first.compareTo(second) >= 0 ? first : second;
    }
}
