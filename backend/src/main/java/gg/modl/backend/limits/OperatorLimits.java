package gg.modl.backend.limits;

import gg.modl.backend.backup.BackupPolicy;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.player.config.IpHistoryPolicy;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.service.CachedSettingsDocument;
import gg.modl.backend.settings.service.SettingsCodec;
import gg.modl.backend.settings.service.SettingsDocumentService;
import gg.modl.backend.settings.service.VersionedSettings;
import gg.modl.backend.storage.UploadLimits;
import gg.modl.backend.ticket.config.NotificationExpiry;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.springframework.stereotype.Service;

@Service
public class OperatorLimits implements UploadLimits, IpHistoryPolicy, NotificationExpiry, BackupPolicy {
    static final String MANAGED_CODE = "OPERATOR_LIMIT_MANAGED_BY_CONFIG";
    static final String OUTSIDE_GROUP_CODE = "OPERATOR_LIMIT_OUTSIDE_GROUP";

    private final OperatorLimitsEnvironment environment;
    private final CachedSettingsDocument<StoredOperatorLimits> document;
    private final PanelActionAuditor auditor;

    public OperatorLimits(OperatorLimitsEnvironment environment, SettingsDocumentService documents, UnitOfWork unitOfWork,
                          PanelActionAuditor auditor) {
        this.environment = environment;
        this.document = new CachedSettingsDocument<>(SettingsType.OPERATOR_LIMITS,
            SettingsCodec.of(StoredOperatorLimits.class, () -> StoredOperatorLimits.EMPTY), documents, unitOfWork);
        this.auditor = auditor;
    }

    public int value(OperatorLimit limit) {
        return environment.value(limit)
            .or(() -> document.current().value(limit))
            .orElse(limit.defaultValue());
    }

    public boolean managed(OperatorLimit limit) {
        return environment.value(limit).isPresent();
    }

    public List<EffectiveLimit> all(OperatorLimitGroup group) {
        return Arrays.stream(OperatorLimit.values())
            .filter(limit -> limit.group() == group)
            .map(limit -> new EffectiveLimit(limit, value(limit), managed(limit)))
            .toList();
    }

    public long version() {
        return document.versioned().version();
    }

    public VersionedSettings<StoredOperatorLimits> change(Server server, String actorEmail, long expectedVersion,
                                                          OperatorLimitGroup group, Map<OperatorLimit, Integer> requested) {
        Map<OperatorLimit, Integer> changes = validated(group, requested);
        Map<OperatorLimit, Integer> before = snapshot();
        VersionedSettings<StoredOperatorLimits> saved = document.updateExpecting(expectedVersion, stored -> stored.with(changes));
        String summary = describeChanges(before, snapshot());
        if (!summary.isEmpty()) {
            auditor.recordStaffAction(server, actorEmail, "Changed operator limits: " + summary);
        }
        return saved;
    }

    @Override
    public long maxUploadBytes() {
        return value(OperatorLimit.MAX_UPLOAD_MB) * BYTES_PER_MB;
    }

    @Override
    public long maxStorageBytes() {
        return value(OperatorLimit.MAX_STORAGE_GB) * BYTES_PER_GB;
    }

    @Override
    public int ipHistoryDays() {
        return value(OperatorLimit.IP_HISTORY_DAYS);
    }

    @Override
    public Duration lifetime() {
        return Duration.ofDays(value(OperatorLimit.NOTIFICATION_DAYS));
    }

    @Override
    public int keep() {
        return value(OperatorLimit.BACKUP_KEEP);
    }

    @Override
    public int hour() {
        return value(OperatorLimit.BACKUP_HOUR);
    }

    private Map<OperatorLimit, Integer> validated(OperatorLimitGroup group, Map<OperatorLimit, Integer> requested) {
        Map<OperatorLimit, Integer> changes = new EnumMap<>(OperatorLimit.class);
        requested.forEach((limit, value) -> {
            if (limit.group() != group) {
                throw new ValidationException(limit.label() + " is not changed here", OUTSIDE_GROUP_CODE);
            }
            if (managed(limit)) {
                if (value != value(limit)) {
                    throw new ConflictException(limit.label() + " is set by " + limit.environmentVariable()
                        + " and cannot be changed from the panel", MANAGED_CODE);
                }
                return;
            }
            if (!limit.admits(value)) {
                throw new ValidationException(limit.rangeDescription());
            }
            changes.put(limit, value);
        });
        return changes;
    }

    private Map<OperatorLimit, Integer> snapshot() {
        Map<OperatorLimit, Integer> values = new EnumMap<>(OperatorLimit.class);
        for (OperatorLimit limit : OperatorLimit.values()) {
            values.put(limit, value(limit));
        }
        return values;
    }

    private static String describeChanges(Map<OperatorLimit, Integer> before, Map<OperatorLimit, Integer> after) {
        StringJoiner changes = new StringJoiner(", ");
        before.forEach((limit, previous) -> {
            Integer next = after.get(limit);
            if (!previous.equals(next)) {
                changes.add(limit.label() + " " + previous + " to " + next);
            }
        });
        return changes.toString();
    }
}
