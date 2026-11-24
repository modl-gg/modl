package gg.modl.backend.migration.service;

import gg.modl.backend.migration.data.MigrationStatus;
import java.time.Instant;
import java.util.Optional;

public record ImportOverview(
    Optional<MigrationStatus> job,
    boolean setupReady,
    long maxUploadBytes,
    int ipHistoryDays,
    Optional<Instant> ipHistoryKeptBefore
) {
}
