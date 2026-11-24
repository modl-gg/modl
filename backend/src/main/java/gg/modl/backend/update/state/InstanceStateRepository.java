package gg.modl.backend.update.state;

import java.time.Instant;
import java.util.Optional;

public interface InstanceStateRepository {
    Optional<InstanceState> find();

    void scheduleNextCheck(Instant nextCheckAt);

    void recordCheck(CheckRecord check);

    void updateSettings(boolean checksEnabled, boolean includePrereleases);

    boolean markNotified(String version);

    void dismiss(String version, String email);

    boolean confirmRunningVersion(String version, Instant at);
}
