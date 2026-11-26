package gg.modl.backend.replay.settings;

import gg.modl.backend.database.DatabaseUnavailableException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.network.FeatureSettingsChanged;
import gg.modl.backend.network.FeatureSettingsSource;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.FieldChanges;
import gg.modl.backend.settings.service.VersionedSettings;
import gg.modl.backend.update.commit.InstanceCommittedEvent;
import gg.modl.proto.modl.v1.FeatureSettingsEvent;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReplaySettingsService implements FeatureSettingsSource {
    private static final FieldChanges<ReplaySettings> CHANGES = FieldChanges.<ReplaySettings>summarizedAs("Changed replay settings: ")
        .compare("replays", ReplaySettings::enabled)
        .compare("record every player", ReplaySettings::recordEveryPlayer)
        .compare("record at anticheat flags", ReplaySettings::recordOnAnticheatFlag)
        .compare("anticheat integration", ReplaySettings::anticheatIntegration)
        .compare("history window", ReplaySettings::windowSeconds)
        .compare("radius", ReplaySettings::radiusBlocks)
        .compare("memory budget", ReplaySettings::memoryBudgetMb)
        .compare("anticheat name", ReplaySettings::anticheatName)
        .build();

    private final ReplaySettingsStore store;
    private final PanelActionAuditor auditor;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public VersionedSettings<ReplaySettings> view() {
        return store.versioned();
    }

    public VersionedSettings<ReplaySettings> update(Server server, @Nullable String actorEmail, long expectedVersion,
                                                    ReplaySettings requested) {
        requireValid(requested);
        ReplaySettings previous = store.versioned().data();
        long chosenAt = clock.millis();
        VersionedSettings<ReplaySettings> saved = store.updateExpecting(expectedVersion, current -> requested.chosenAt(chosenAt));
        events.publishEvent(new FeatureSettingsChanged(ReplaySettingsProtos.toFeatureSettings(saved)));
        CHANGES.summary(previous, saved.data()).ifPresent(summary -> auditor.recordStaffAction(server, actorEmail, summary));
        return saved;
    }

    @Override
    public FeatureSettingsEvent current() {
        return ReplaySettingsProtos.toFeatureSettings(store.versioned());
    }

    @EventListener(InstanceCommittedEvent.class)
    public void loadOnCommit() {
        try {
            store.versioned();
        } catch (DatabaseUnavailableException e) {
            log.debug("Skipped loading the replay settings while the database is unavailable", e);
        }
    }

    private static void requireValid(ReplaySettings requested) {
        if (!ReplaySettings.validWindow(requested.windowSeconds())) {
            throw new ValidationException("The history window must be between " + ReplaySettings.MIN_WINDOW_SECONDS + " and "
                + ReplaySettings.MAX_WINDOW_SECONDS + " seconds");
        }
        if (!ReplaySettings.validRadius(requested.radiusBlocks())) {
            throw new ValidationException("The radius must be between " + ReplaySettings.MIN_RADIUS_BLOCKS + " and "
                + ReplaySettings.MAX_RADIUS_BLOCKS + " blocks");
        }
        if (!ReplaySettings.validMemoryBudget(requested.memoryBudgetMb())) {
            throw new ValidationException("The memory budget must be between " + ReplaySettings.MIN_MEMORY_BUDGET_MB + " and "
                + ReplaySettings.MAX_MEMORY_BUDGET_MB + " MB");
        }
        if (!ReplaySettings.validAnticheatName(requested.anticheatName())) {
            throw new ValidationException("The anticheat name must have 1 to " + ReplaySettings.MAX_ANTICHEAT_NAME_LENGTH
                + " characters");
        }
    }
}
