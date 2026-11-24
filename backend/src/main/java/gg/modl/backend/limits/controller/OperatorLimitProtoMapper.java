package gg.modl.backend.limits.controller;

import gg.modl.backend.limits.EffectiveLimit;
import gg.modl.backend.limits.OperatorLimit;
import gg.modl.proto.modl.v1.OperatorLimitKey;
import gg.modl.proto.modl.v1.OperatorLimitValue;
import gg.modl.proto.modl.v1.OperatorLimitsResponse;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class OperatorLimitProtoMapper {
    private OperatorLimitProtoMapper() {
    }

    public static Map<OperatorLimit, Integer> toChanges(List<OperatorLimitValue> values) {
        Map<OperatorLimit, Integer> changes = new EnumMap<>(OperatorLimit.class);
        values.forEach(value -> toLimit(value.getKey()).ifPresent(limit -> changes.put(limit, value.getValue())));
        return changes;
    }

    public static List<gg.modl.proto.modl.v1.OperatorLimit> toLimits(List<EffectiveLimit> limits) {
        return limits.stream().map(OperatorLimitProtoMapper::toLimit).toList();
    }

    static OperatorLimitsResponse toResponse(List<EffectiveLimit> limits, long version) {
        return OperatorLimitsResponse.newBuilder().addAllLimits(toLimits(limits)).setLimitsVersion(version).build();
    }

    private static gg.modl.proto.modl.v1.OperatorLimit toLimit(EffectiveLimit limit) {
        OperatorLimit definition = limit.limit();
        return gg.modl.proto.modl.v1.OperatorLimit.newBuilder()
            .setKey(toKey(definition))
            .setValue(limit.value())
            .setMin(definition.min())
            .setMax(definition.max())
            .setDefaultValue(definition.defaultValue())
            .setManaged(limit.managed())
            .setEnvironmentVariable(definition.environmentVariable())
            .build();
    }

    private static OperatorLimitKey toKey(OperatorLimit limit) {
        return switch (limit) {
            case MAX_UPLOAD_MB -> OperatorLimitKey.OPERATOR_LIMIT_KEY_MAX_UPLOAD_MB;
            case MAX_STORAGE_GB -> OperatorLimitKey.OPERATOR_LIMIT_KEY_MAX_STORAGE_GB;
            case BACKUP_KEEP -> OperatorLimitKey.OPERATOR_LIMIT_KEY_BACKUP_KEEP;
            case BACKUP_HOUR -> OperatorLimitKey.OPERATOR_LIMIT_KEY_BACKUP_HOUR;
            case IP_HISTORY_DAYS -> OperatorLimitKey.OPERATOR_LIMIT_KEY_IP_HISTORY_DAYS;
            case NOTIFICATION_DAYS -> OperatorLimitKey.OPERATOR_LIMIT_KEY_NOTIFICATION_DAYS;
        };
    }

    private static Optional<OperatorLimit> toLimit(OperatorLimitKey key) {
        return switch (key) {
            case OPERATOR_LIMIT_KEY_MAX_UPLOAD_MB -> Optional.of(OperatorLimit.MAX_UPLOAD_MB);
            case OPERATOR_LIMIT_KEY_MAX_STORAGE_GB -> Optional.of(OperatorLimit.MAX_STORAGE_GB);
            case OPERATOR_LIMIT_KEY_BACKUP_KEEP -> Optional.of(OperatorLimit.BACKUP_KEEP);
            case OPERATOR_LIMIT_KEY_BACKUP_HOUR -> Optional.of(OperatorLimit.BACKUP_HOUR);
            case OPERATOR_LIMIT_KEY_IP_HISTORY_DAYS -> Optional.of(OperatorLimit.IP_HISTORY_DAYS);
            case OPERATOR_LIMIT_KEY_NOTIFICATION_DAYS -> Optional.of(OperatorLimit.NOTIFICATION_DAYS);
            case OPERATOR_LIMIT_KEY_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
        };
    }
}
