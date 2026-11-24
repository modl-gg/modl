package gg.modl.backend.settings.service;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.ReplayRetentionSettings;
import gg.modl.backend.settings.data.SettingsType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ReplayRetentionSettingsService {
    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 365;

    private final VersionedSettingsSupport<ReplayRetentionSettings> support;
    private final PanelActionAuditor auditor;

    public ReplayRetentionSettingsService(SettingsDocumentService settingsDocumentService, PanelActionAuditor auditor) {
        this.support = VersionedSettingsSupport.of(
            settingsDocumentService, SettingsType.REPLAY_RETENTION, this::mapToSettings);
        this.auditor = auditor;
    }

    public ReplayRetentionSettings getReplayRetentionSettings(Server server) {
        return support.get(server);
    }

    public VersionedSettings<ReplayRetentionSettings> getReplayRetentionSettingsState(Server server) {
        return support.state(server);
    }

    public VersionedSettings<ReplayRetentionSettings> patchReplayRetentionSettings(
        Server server,
        String actorEmail,
        long expectedVersion,
        Boolean enabled,
        Integer days
    ) {
        ReplayRetentionSettings current = support.get(server);
        VersionedSettings<ReplayRetentionSettings> saved =
            support.update(server, expectedVersion, data -> patched(data, enabled, days));
        if (!saved.data().equals(current)) {
            auditor.recordStaffAction(server, actorEmail, "Changed replay retention: " + describe(current) + " to "
                + describe(saved.data()));
        }
        return saved;
    }

    private Map<String, Object> patched(Map<String, Object> data, Boolean enabled, Integer days) {
        ReplayRetentionSettings merged = mapToSettings(data);
        if (enabled != null) {
            merged.setEnabled(enabled);
        }
        if (days != null) {
            merged.setDays(days);
        }
        ReplayRetentionSettings normalized = validateForWrite(merged);
        data.put("enabled", normalized.isEnabled());
        data.put("days", normalized.getDays());
        return data;
    }

    private static String describe(ReplayRetentionSettings settings) {
        return settings.isEnabled() ? settings.getDays() + " days" : "kept until deleted";
    }

    private ReplayRetentionSettings mapToSettings(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return ReplayRetentionSettings.defaults();
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        ReplayRetentionSettings defaults = ReplayRetentionSettings.defaults();
        merged.put("enabled", defaults.isEnabled());
        merged.put("days", defaults.getDays());
        merged.putAll(data);
        return normalizeForRead(codec().decode(merged));
    }

    private SettingsCodec<ReplayRetentionSettings> codec() {
        return SettingsCodec.of(ReplayRetentionSettings.class, ReplayRetentionSettings::defaults);
    }

    private ReplayRetentionSettings normalizeForRead(ReplayRetentionSettings settings) {
        ReplayRetentionSettings normalized = settings != null ? settings : ReplayRetentionSettings.defaults();
        if (normalized.getDays() < MIN_DAYS) {
            normalized.setDays(MIN_DAYS);
        } else if (normalized.getDays() > MAX_DAYS) {
            normalized.setDays(MAX_DAYS);
        }
        return normalized;
    }

    private ReplayRetentionSettings validateForWrite(ReplayRetentionSettings settings) {
        ReplayRetentionSettings normalized = settings != null ? settings : ReplayRetentionSettings.defaults();
        if (normalized.getDays() < MIN_DAYS) {
            if (normalized.isEnabled()) {
                throw new ValidationException("Replay retention days must be at least 1 when enabled");
            }
            normalized.setDays(MIN_DAYS);
        }
        if (normalized.getDays() > MAX_DAYS) {
            if (normalized.isEnabled()) {
                throw new ValidationException("Replay retention days must be at most 365");
            }
            normalized.setDays(MAX_DAYS);
        }
        return normalized;
    }
}
