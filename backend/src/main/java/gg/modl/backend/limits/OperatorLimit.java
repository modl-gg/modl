package gg.modl.backend.limits;

public enum OperatorLimit {
    MAX_UPLOAD_MB(OperatorLimitGroup.STORAGE, "maxUploadMb", "MODL_MAX_UPLOAD_MB", "Maximum upload size (MB)", 100, 1, 10_240),
    MAX_STORAGE_GB(OperatorLimitGroup.STORAGE, "maxStorageGb", "MODL_MAX_STORAGE_GB", "Total upload storage (GB, 0 = no limit)", 0, 0,
        1_000_000),
    BACKUP_KEEP(OperatorLimitGroup.STORAGE, "backupKeep", "MODL_BACKUP_KEEP", "Nightly backups kept", 7, 1, 365),
    BACKUP_HOUR(OperatorLimitGroup.STORAGE, "backupHour", "MODL_BACKUP_HOUR", "Nightly backup hour", 4, 0, 23),
    IP_HISTORY_DAYS(OperatorLimitGroup.RETENTION, "ipHistoryDays", "MODL_IP_HISTORY_DAYS", "IP history retention (days)", 365, 1, 3650),
    NOTIFICATION_DAYS(OperatorLimitGroup.RETENTION, "notificationDays", "MODL_NOTIFICATION_EXPIRY_DAYS",
        "Player notification retention (days)", 90, 1, 3650);

    private final OperatorLimitGroup group;
    private final String documentKey;
    private final String environmentVariable;
    private final String label;
    private final int defaultValue;
    private final int min;
    private final int max;

    OperatorLimit(OperatorLimitGroup group, String documentKey, String environmentVariable, String label, int defaultValue, int min,
                  int max) {
        this.group = group;
        this.documentKey = documentKey;
        this.environmentVariable = environmentVariable;
        this.label = label;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
    }

    public OperatorLimitGroup group() {
        return group;
    }

    public String documentKey() {
        return documentKey;
    }

    public String environmentVariable() {
        return environmentVariable;
    }

    public String label() {
        return label;
    }

    public int defaultValue() {
        return defaultValue;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    public boolean admits(int value) {
        return value >= min && value <= max;
    }

    public String rangeDescription() {
        return label + " must be between " + min + " and " + max;
    }
}
