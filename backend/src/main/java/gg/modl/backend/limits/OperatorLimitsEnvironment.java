package gg.modl.backend.limits;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "modl.limits")
public record OperatorLimitsEnvironment(
    @Nullable Integer maxUploadMb,
    @Nullable Integer maxStorageGb,
    @Nullable Integer backupKeep,
    @Nullable Integer backupHour,
    @Nullable Integer ipHistoryDays,
    @Nullable Integer notificationDays
) {
    public static final OperatorLimitsEnvironment NONE = new OperatorLimitsEnvironment(null, null, null, null, null, null);

    public OperatorLimitsEnvironment {
        requireWithin(OperatorLimit.MAX_UPLOAD_MB, maxUploadMb);
        requireWithin(OperatorLimit.MAX_STORAGE_GB, maxStorageGb);
        requireWithin(OperatorLimit.BACKUP_KEEP, backupKeep);
        requireWithin(OperatorLimit.BACKUP_HOUR, backupHour);
        requireWithin(OperatorLimit.IP_HISTORY_DAYS, ipHistoryDays);
        requireWithin(OperatorLimit.NOTIFICATION_DAYS, notificationDays);
    }

    public Optional<Integer> value(OperatorLimit limit) {
        return Optional.ofNullable(values().get(limit));
    }

    private Map<OperatorLimit, Integer> values() {
        Map<OperatorLimit, Integer> values = new EnumMap<>(OperatorLimit.class);
        putIfSet(values, OperatorLimit.MAX_UPLOAD_MB, maxUploadMb);
        putIfSet(values, OperatorLimit.MAX_STORAGE_GB, maxStorageGb);
        putIfSet(values, OperatorLimit.BACKUP_KEEP, backupKeep);
        putIfSet(values, OperatorLimit.BACKUP_HOUR, backupHour);
        putIfSet(values, OperatorLimit.IP_HISTORY_DAYS, ipHistoryDays);
        putIfSet(values, OperatorLimit.NOTIFICATION_DAYS, notificationDays);
        return values;
    }

    private static void putIfSet(Map<OperatorLimit, Integer> values, OperatorLimit limit, @Nullable Integer value) {
        if (value != null) {
            values.put(limit, value);
        }
    }

    private static void requireWithin(OperatorLimit limit, @Nullable Integer value) {
        if (value != null && !limit.admits(value)) {
            throw new IllegalArgumentException(limit.environmentVariable() + " must be between " + limit.min() + " and "
                + limit.max() + ", not " + value);
        }
    }
}
