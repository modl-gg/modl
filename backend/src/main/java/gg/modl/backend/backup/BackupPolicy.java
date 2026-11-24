package gg.modl.backend.backup;

public interface BackupPolicy {
    int keep();

    int hour();

    static BackupPolicy of(int keep, int hour) {
        if (keep < 1 || hour < 0 || hour > 23) {
            throw new IllegalArgumentException("A backup policy keeps at least 1 backup at an hour from 0 to 23");
        }
        return new BackupPolicy() {
            @Override
            public int keep() {
                return keep;
            }

            @Override
            public int hour() {
                return hour;
            }
        };
    }
}
