package gg.modl.backend.settings.service;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.data.TicketFormSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public class TicketFormSettingsService {

    private final VersionedSettingsSupport<TicketFormSettings> support;

    public TicketFormSettingsService(SettingsDocumentService settingsDocumentService) {
        this.support = VersionedSettingsSupport.of(
            settingsDocumentService, SettingsType.TICKET_FORMS, this::mapToTicketFormSettings);
    }

    public VersionedSettings<TicketFormSettings> patchTicketFormSettings(
        Server server,
        long expectedVersion,
        TicketFormSettings newSettings
    ) {
        TicketFormSettings merged = newSettings != null ? newSettings : getDefaultTicketFormSettings();
        ensureFormDefaults(merged);
        return support.save(server, expectedVersion, codec().encode(merged));
    }

    public TicketFormSettings.@Nullable TicketForm getFormByType(Server server, String formType) {
        TicketFormSettings settings = getTicketFormSettings(server);

        return switch (formType.toLowerCase(Locale.ROOT)) {
            case "bug" -> settings.getBug();
            case "support" -> settings.getSupport();
            case "application", "staff" -> settings.getApplication();
            case "player" -> settings.getPlayer();
            case "chat" -> settings.getChat();
            default -> null;
        };
    }

    public TicketFormSettings getTicketFormSettings(Server server) {
        return support.get(server);
    }

    public VersionedSettings<TicketFormSettings> getTicketFormSettingsState(Server server) {
        return support.state(server);
    }

    private TicketFormSettings mapToTicketFormSettings(Map<String, Object> data) {
        TicketFormSettings mapped = codec().decode(data);
        ensureFormDefaults(mapped);
        return mapped;
    }

    private SettingsCodec<TicketFormSettings> codec() {
        return SettingsCodec.of(TicketFormSettings.class, this::getDefaultTicketFormSettings);
    }

    private void ensureFormDefaults(TicketFormSettings settings) {
        if (settings.getBug() == null) {
            settings.setBug(emptyForm());
        }
        if (settings.getSupport() == null) {
            settings.setSupport(emptyForm());
        }
        if (settings.getApplication() == null) {
            settings.setApplication(emptyForm());
        }
        if (settings.getPlayer() == null) {
            settings.setPlayer(emptyForm());
        }
        if (settings.getChat() == null) {
            settings.setChat(emptyForm());
        }

        for (TicketFormSettings.TicketForm form : List.of(
            settings.getBug(), settings.getSupport(), settings.getApplication(),
            settings.getPlayer(), settings.getChat()
        )) {
            sanitizeForm(form);
        }
    }

    private void sanitizeForm(TicketFormSettings.TicketForm form) {
        if (form.getFields() == null) {
            form.setFields(new ArrayList<>());
        }
        if (form.getSections() == null) {
            form.setSections(new ArrayList<>());
        }
        if (form.getAllowEmailNotifications() == null) {
            form.setAllowEmailNotifications(true);
        }
    }

    private TicketFormSettings getDefaultTicketFormSettings() {
        return TicketFormSettings.builder()
            .bug(emptyForm())
            .support(emptyForm())
            .application(emptyForm())
            .player(emptyForm())
            .chat(emptyForm())
            .build();
    }

    private TicketFormSettings.TicketForm emptyForm() {
        return TicketFormSettings.TicketForm.builder()
            .allowEmailNotifications(true)
            .fields(new ArrayList<>())
            .sections(new ArrayList<>())
            .build();
    }

    public Map<String, Object> buildTicketFormsResponse(TicketFormSettings ticketForms) {
        Map<String, Object> forms = new HashMap<>();
        putFormIfNotNull(forms, "bug", ticketForms.getBug());
        putFormIfNotNull(forms, "support", ticketForms.getSupport());
        putFormIfNotNull(forms, "application", ticketForms.getApplication());
        putFormIfNotNull(forms, "player", ticketForms.getPlayer());
        putFormIfNotNull(forms, "chat", ticketForms.getChat());
        return forms;
    }

    private void putFormIfNotNull(Map<String, Object> forms, String key, TicketFormSettings.TicketForm form) {
        if (form != null) {
            forms.put(key, SettingsCodec.toDocument(form));
        }
    }
}
