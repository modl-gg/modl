package gg.modl.backend.status.facts;

import gg.modl.backend.backup.BackupPolicy;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.backup.NightlyBackups;
import gg.modl.backend.status.StatusContributor;
import gg.modl.backend.status.StatusFact;
import gg.modl.backend.status.StatusItem;
import gg.modl.backend.status.StatusSection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BackupStatusFacts implements StatusContributor {
    private static final Duration OVERDUE = Duration.ofHours(26);

    private final NightlyBackups nightlyBackups;
    private final BackupPolicy policy;
    private final DatabaseDialect dialect;
    private final Clock clock;

    @Override
    public StatusSection section() {
        return StatusSection.BACKUPS;
    }

    @Override
    public List<StatusFact> facts() {
        if (dialect.isExternal()) {
            return List.of(StatusItem.BACKUP_LATEST.ok("not made by modl for an external database"));
        }
        List<Path> archives;
        try {
            archives = nightlyBackups.archives();
        } catch (IOException e) {
            return List.of(StatusItem.BACKUP_LATEST.problem("the backup folder cannot be read"));
        }
        return List.of(latest(archives), StatusItem.BACKUP_COUNT.ok(archives.size() + " of " + policy.keep()),
            StatusItem.BACKUP_SCHEDULE.ok("daily at " + "%02d:00".formatted(policy.hour()) + " " + ZoneId.systemDefault().getId()));
    }

    private StatusFact latest(List<Path> archives) {
        if (archives.isEmpty()) {
            return StatusItem.BACKUP_LATEST.warning("none yet");
        }
        try {
            Instant written = Files.getLastModifiedTime(archives.getFirst()).toInstant();
            String text = StatusFormats.instant(written);
            return written.isBefore(clock.instant().minus(OVERDUE)) ? StatusItem.BACKUP_LATEST.warning(text + ", overdue")
                : StatusItem.BACKUP_LATEST.ok(text);
        } catch (IOException e) {
            return StatusItem.BACKUP_LATEST.warning("unknown");
        }
    }
}
