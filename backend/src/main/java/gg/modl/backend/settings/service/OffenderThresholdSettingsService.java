package gg.modl.backend.settings.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.OffenderThresholdSettings;
import gg.modl.backend.settings.data.SettingsType;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OffenderThresholdSettingsService {
    private static final int MIN_THRESHOLD = 0;
    private static final int MAX_THRESHOLD = 10_000;
    private static final int MIN_POINT_EXPIRY_MONTHS = 1;
    private static final int MAX_POINT_EXPIRY_MONTHS = 60;

    private final VersionedSettingsSupport<OffenderThresholdSettings> support;
    private final UnitOfWork unitOfWork;
    private final SettingsCache<OffenderThresholdSettings> thresholdCache;

    public OffenderThresholdSettingsService(SettingsDocumentService settingsDocumentService, UnitOfWork unitOfWork, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.thresholdCache = new SettingsCache<>(clock);
        this.support = VersionedSettingsSupport.of(
            settingsDocumentService, SettingsType.STATUS_THRESHOLDS, this::mapToThresholdSettings);
    }

    public OffenderThresholdSettings getThresholdSettings(Server server) {
        return thresholdCache.get(() -> support.get(server));
    }

    public VersionedSettings<OffenderThresholdSettings> getThresholdSettingsState(Server server) {
        return support.state(server);
    }

    public VersionedSettings<OffenderThresholdSettings> patchThresholdSettings(
        Server server,
        long expectedVersion,
        OffenderThresholdSettings patch
    ) {
        return unitOfWork.write(() -> {
            VersionedSettings<OffenderThresholdSettings> saved =
                support.update(server, expectedVersion, data -> patched(data, patch));
            unitOfWork.afterCommit(thresholdCache::invalidate);
            return saved;
        });
    }

    private Map<String, Object> patched(Map<String, Object> data, OffenderThresholdSettings patch) {
        OffenderThresholdSettings current = mapToThresholdSettings(data);
        if (patch != null) {
            if (patch.getSocial() != null) {
                current.setSocial(sanitizeCategoryThresholds(patch.getSocial()));
            }
            if (patch.getGameplay() != null) {
                current.setGameplay(sanitizeCategoryThresholds(patch.getGameplay()));
            }
        }
        return codec().encode(normalizeSettings(current));
    }

    private OffenderThresholdSettings mapToThresholdSettings(Map<String, Object> data) {
        OffenderThresholdSettings mapped = codec().decode(data);
        if (mapped.getSocial() == null || mapped.getGameplay() == null) {
            return OffenderThresholdSettings.defaults();
        }
        return normalizeSettings(mapped);
    }

    private SettingsCodec<OffenderThresholdSettings> codec() {
        return SettingsCodec.of(OffenderThresholdSettings.class, OffenderThresholdSettings::defaults);
    }

    private OffenderThresholdSettings normalizeSettings(OffenderThresholdSettings settings) {
        OffenderThresholdSettings normalized = settings != null ? settings : OffenderThresholdSettings.defaults();
        if (normalized.getSocial() == null) {
            normalized.setSocial(OffenderThresholdSettings.defaults().getSocial());
        }
        if (normalized.getGameplay() == null) {
            normalized.setGameplay(OffenderThresholdSettings.defaults().getGameplay());
        }

        normalized.setSocial(sanitizeCategoryThresholds(normalized.getSocial()));
        normalized.setGameplay(sanitizeCategoryThresholds(normalized.getGameplay()));
        return normalized;
    }

    private OffenderThresholdSettings.CategoryThresholds sanitizeCategoryThresholds(
        OffenderThresholdSettings.CategoryThresholds thresholds
    ) {
        int medium = sanitizeThresholdValue(thresholds.getMedium());
        int habitual = sanitizeThresholdValue(thresholds.getHabitual());
        int pointExpiryMonths = sanitizePointExpiryMonths(thresholds.getPointExpiryMonths());
        if (habitual < medium) {
            habitual = medium;
        }
        return new OffenderThresholdSettings.CategoryThresholds(medium, habitual, pointExpiryMonths);
    }

    private int sanitizeThresholdValue(int value) {
        return Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, value));
    }

    private int sanitizePointExpiryMonths(int value) {
        return Math.max(MIN_POINT_EXPIRY_MONTHS, Math.min(MAX_POINT_EXPIRY_MONTHS, value));
    }
}
