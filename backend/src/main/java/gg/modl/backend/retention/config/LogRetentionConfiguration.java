package gg.modl.backend.retention.config;

import gg.modl.backend.log.repository.AuditLogRepository;
import gg.modl.backend.log.repository.ChatLogRepository;
import gg.modl.backend.log.repository.CommandLogRepository;
import gg.modl.backend.retention.RetentionBatches;
import gg.modl.backend.retention.service.LogRetentionTask;
import gg.modl.backend.settings.data.DataRetentionSettings;
import gg.modl.backend.settings.service.DataRetentionSettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LogRetentionConfiguration {

    @Bean
    LogRetentionTask chatLogRetention(ChatLogRepository chatLogs, DataRetentionSettingsService settingsService) {
        return new LogRetentionTask("chat logs", chatLogs, DataRetentionSettings::getChatLogDays, settingsService,
            RetentionBatches.DEFAULT_BATCH_SIZE, RetentionBatches.DEFAULT_MAX_BATCHES_PER_RUN);
    }

    @Bean
    LogRetentionTask commandLogRetention(CommandLogRepository commandLogs, DataRetentionSettingsService settingsService) {
        return new LogRetentionTask("command logs", commandLogs, DataRetentionSettings::getChatLogDays, settingsService,
            RetentionBatches.DEFAULT_BATCH_SIZE, RetentionBatches.DEFAULT_MAX_BATCHES_PER_RUN);
    }

    @Bean
    LogRetentionTask auditLogRetention(AuditLogRepository auditLogs, DataRetentionSettingsService settingsService) {
        return new LogRetentionTask("audit log entries", auditLogs, DataRetentionSettings::getAuditLogDays, settingsService,
            RetentionBatches.DEFAULT_BATCH_SIZE, RetentionBatches.DEFAULT_MAX_BATCHES_PER_RUN);
    }
}
