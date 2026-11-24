package gg.modl.backend.settings.service;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.QuickResponseSettings;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.dto.request.UpdateQuickResponsesRequest;
import java.util.ArrayList;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public class QuickResponseSettingsService {

    private final VersionedSettingsSupport<QuickResponseSettings> support;

    public QuickResponseSettingsService(SettingsDocumentService settingsDocumentService) {
        this.support = VersionedSettingsSupport.of(
            settingsDocumentService, SettingsType.QUICK_RESPONSES, this::mapToQuickResponseSettings);
    }

    public QuickResponseSettings getQuickResponseSettings(Server server) {
        return support.get(server);
    }

    public VersionedSettings<QuickResponseSettings> getQuickResponseSettingsState(Server server) {
        return support.state(server);
    }

    public QuickResponseSettings.@Nullable Action findAction(QuickResponseSettings settings, @Nullable String categoryId,
                                                             @Nullable String actionId) {
        if (settings.getCategories() == null || categoryId == null || actionId == null) {
            return null;
        }

        for (QuickResponseSettings.Category category : settings.getCategories()) {
            if (category == null || category.getId() == null || category.getActions() == null) {
                continue;
            }
            if (categoryId.equals(category.getId())) {
                for (QuickResponseSettings.Action action : category.getActions()) {
                    if (action != null && actionId.equals(action.getId())) {
                        return action;
                    }
                }
            }
        }

        return null;
    }

    public VersionedSettings<QuickResponseSettings> patchQuickResponseSettings(
        Server server,
        long expectedVersion,
        UpdateQuickResponsesRequest quickResponses
    ) {
        return support.update(server, expectedVersion, data -> {
            QuickResponseSettings settings = mapToQuickResponseSettings(data);
            if (quickResponses != null && quickResponses.categories() != null) {
                settings.setCategories(quickResponses.categories());
            }
            return codec().encode(settings);
        });
    }

    private QuickResponseSettings mapToQuickResponseSettings(Map<String, Object> data) {
        QuickResponseSettings mapped = codec().decode(data);
        if (mapped.getCategories() == null) {
            mapped.setCategories(new ArrayList<>());
        }
        return mapped;
    }

    private SettingsCodec<QuickResponseSettings> codec() {
        return SettingsCodec.of(QuickResponseSettings.class, this::defaultQuickResponseSettings);
    }

    private QuickResponseSettings defaultQuickResponseSettings() {
        QuickResponseSettings settings = new QuickResponseSettings();
        settings.setCategories(new ArrayList<>());
        return settings;
    }
}
