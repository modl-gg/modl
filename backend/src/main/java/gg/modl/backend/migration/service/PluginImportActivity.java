package gg.modl.backend.migration.service;

import gg.modl.backend.migration.repository.ImportJobRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class PluginImportActivity implements ConcurrentImport {
    private final ImportJobRepository jobs;
    private final Clock clock;

    @Override
    public ImportKind kind() {
        return ImportKind.PLUGIN;
    }

    @Override
    public boolean isInProgress() {
        Instant now = clock.instant();
        return jobs.findActive().filter(job -> !job.hasGoneStale(now)).isPresent();
    }
}
