package gg.modl.backend.settings.service;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.DataRetentionSettings;
import gg.modl.backend.settings.data.DataRetentionSettings.RetentionPeriod;
import gg.modl.backend.settings.data.SettingsType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public class DataRetentionSettingsService {
    private static final String CHAT_LOG_DAYS = "chatLogDays";
    private static final String AUDIT_LOG_DAYS = "auditLogDays";
    private static final SettingsCodec<DataRetentionSettings> CODEC =
        SettingsCodec.of(DataRetentionSettings.class, DataRetentionSettings::defaults);

    private final VersionedSettingsSupport<DataRetentionSettings> support;
    private final PanelActionAuditor auditor;

    public DataRetentionSettingsService(SettingsDocumentService settingsDocumentService, PanelActionAuditor auditor) {
        this.support = VersionedSettingsSupport.of(settingsDocumentService, SettingsType.DATA_RETENTION, DataRetentionSettingsService::decode);
        this.auditor = auditor;
    }

    public DataRetentionSettings getDataRetentionSettings(Server server) {
        return support.get(server);
    }

    public VersionedSettings<DataRetentionSettings> getDataRetentionSettingsState(Server server) {
        return support.state(server);
    }

    public VersionedSettings<DataRetentionSettings> patchDataRetentionSettings(
        Server server,
        String actorEmail,
        long expectedVersion,
        @Nullable Integer chatLogDays,
        @Nullable Integer auditLogDays
    ) {
        DataRetentionSettings current = support.get(server);
        VersionedSettings<DataRetentionSettings> saved =
            support.update(server, expectedVersion, data -> patched(data, chatLogDays, auditLogDays));
        if (!saved.data().equals(current)) {
            auditor.recordStaffAction(server, actorEmail, "Changed data retention: chat and command logs "
                + current.getChatLogDays() + " to " + saved.data().getChatLogDays() + " days, audit log "
                + current.getAuditLogDays() + " to " + saved.data().getAuditLogDays() + " days");
        }
        return saved;
    }

    private static Map<String, Object> patched(
        Map<String, Object> data,
        @Nullable Integer chatLogDays,
        @Nullable Integer auditLogDays
    ) {
        DataRetentionSettings stored = decode(data);
        data.put(CHAT_LOG_DAYS, requireWithin(chatLogDays, stored.getChatLogDays(), DataRetentionSettings.CHAT_LOG_PERIOD,
            "Chat and command log retention"));
        data.put(AUDIT_LOG_DAYS, requireWithin(auditLogDays, stored.getAuditLogDays(), DataRetentionSettings.AUDIT_LOG_PERIOD,
            "Audit log retention"));
        return data;
    }

    private static DataRetentionSettings decode(Map<String, Object> data) {
        Map<String, Object> merged = new LinkedHashMap<>(CODEC.encode(DataRetentionSettings.defaults()));
        if (data != null) {
            merged.putAll(data);
        }
        DataRetentionSettings decoded = CODEC.decode(merged);
        decoded.setChatLogDays(DataRetentionSettings.CHAT_LOG_PERIOD.clamp(decoded.getChatLogDays()));
        decoded.setAuditLogDays(DataRetentionSettings.AUDIT_LOG_PERIOD.clamp(decoded.getAuditLogDays()));
        return decoded;
    }

    private static int requireWithin(@Nullable Integer requested, int current, RetentionPeriod period, String label) {
        int days = requested != null ? requested : current;
        if (!period.admits(days)) {
            throw new ValidationException(label + " must be between " + period.minDays() + " and " + period.maxDays() + " days");
        }
        return days;
    }
}
