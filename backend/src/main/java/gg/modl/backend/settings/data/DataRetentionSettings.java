package gg.modl.backend.settings.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataRetentionSettings {
    public static final RetentionPeriod CHAT_LOG_PERIOD = new RetentionPeriod(1, 3650);
    public static final RetentionPeriod AUDIT_LOG_PERIOD = new RetentionPeriod(30, 3650);

    private int chatLogDays;
    private int auditLogDays;

    public static DataRetentionSettings defaults() {
        return DataRetentionSettings.builder()
            .chatLogDays(30)
            .auditLogDays(365)
            .build();
    }

    public record RetentionPeriod(int minDays, int maxDays) {

        public int clamp(int days) {
            return Math.clamp(days, minDays, maxDays);
        }

        public boolean admits(int days) {
            return days >= minDays && days <= maxDays;
        }
    }
}
