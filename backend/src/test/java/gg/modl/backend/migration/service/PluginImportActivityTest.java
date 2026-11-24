package gg.modl.backend.migration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.migration.data.ImportSource;
import gg.modl.backend.migration.data.ImportState;
import gg.modl.backend.migration.data.MigrationStatus;
import gg.modl.backend.migration.repository.ImportJobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PluginImportActivityTest {
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private final ImportJobRepository jobs = mock(ImportJobRepository.class);
    private final PluginImportActivity activity = new PluginImportActivity(jobs, Clock.fixed(NOW, ZoneOffset.UTC));

    @ParameterizedTest
    @EnumSource(ImportSource.class)
    void aJobOfAnySourceThatStillReportsCountsAsARunningImport(ImportSource source) {
        when(jobs.findActive()).thenReturn(Optional.of(job(source, NOW.minusSeconds(10))));

        assertThat(activity.isInProgress()).isTrue();
    }

    @Test
    void noJobOrAStaleJobDoesNotHoldBackOtherWork() {
        when(jobs.findActive()).thenReturn(Optional.empty());
        assertThat(activity.isInProgress()).isFalse();

        when(jobs.findActive()).thenReturn(Optional.of(job(ImportSource.LITEBANS, NOW.minus(Duration.ofDays(1)))));
        assertThat(activity.isInProgress()).isFalse();
    }

    private static MigrationStatus job(ImportSource source, Instant lastProgress) {
        return MigrationStatus.builder()
            .id("job")
            .type(source.id())
            .status(ImportState.BUILDING)
            .startedAt(lastProgress)
            .lastProgressAt(lastProgress)
            .build();
    }
}
