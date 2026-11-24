package gg.modl.backend.settings.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.instance.InstanceService;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.instance.ServerNames;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.GeneralSettings;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.data.SupportedLanguages;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public class GeneralSettingsService {
    static final String SERVER_NAME_MANAGED_CODE = "SERVER_NAME_MANAGED_BY_CONFIG";
    static final String PUBLIC_URL_MANAGED_CODE = "PUBLIC_URL_MANAGED_BY_CONFIG";
    private static final int MAX_URL_LENGTH = 2048;
    private static final String HOMEPAGE_ICON_URL = "homepageIconUrl";
    private static final String PANEL_ICON_URL = "panelIconUrl";
    private static final String DEFAULT_LANGUAGE = "defaultLanguage";
    private static final String BLANK_SERVER_NAME_MESSAGE = "The server name cannot be empty";
    private static final String MANAGED_PUBLIC_URL_MESSAGE =
        "The panel URL is set by MODL_PUBLIC_URL or connection.yml (embedded.public-url) and cannot be changed from the panel";
    private static final String MANAGED_SERVER_NAME_MESSAGE =
        "The server name is set by MODL_SERVER_NAME and cannot be changed from the panel";
    private static final String INVALID_PUBLIC_URL_MESSAGE =
        "The panel URL must be an http(s) origin such as https://panel.example.com";

    private final PublicUrls publicUrls;
    private final InstanceService instanceService;
    private final InstanceProperties instanceProperties;
    private final UnitOfWork unitOfWork;
    private final VersionedSettingsSupport<GeneralSettings> support;

    public GeneralSettingsService(
        SettingsDocumentService settingsDocumentService,
        UnitOfWork unitOfWork,
        PublicUrls publicUrls,
        InstanceService instanceService,
        InstanceProperties instanceProperties
    ) {
        this.unitOfWork = unitOfWork;
        this.publicUrls = publicUrls;
        this.instanceService = instanceService;
        this.instanceProperties = instanceProperties;
        this.support = VersionedSettingsSupport.of(
            settingsDocumentService, SettingsType.GENERAL, this::mapToGeneralSettings);
    }

    public GeneralSettings getGeneralSettings(Server server) {
        return withInstanceIdentity(server, support.get(server));
    }

    public VersionedSettings<GeneralSettings> getGeneralSettingsState(Server server) {
        return withInstanceIdentity(server, support.state(server));
    }

    private VersionedSettings<GeneralSettings> withInstanceIdentity(Server server, VersionedSettings<GeneralSettings> state) {
        return new VersionedSettings<>(withInstanceIdentity(server, state.data()), state.version(), state.updatedAt());
    }

    private GeneralSettings withInstanceIdentity(Server server, GeneralSettings settings) {
        return settings.toBuilder()
            .serverDisplayName(server.getServerName())
            .publicUrl(publicUrls.origin())
            .publicUrlManagedByConfig(publicUrls.isManagedByConfig())
            .serverNameManaged(isServerNameManaged())
            .build();
    }

    private boolean isServerNameManaged() {
        return instanceProperties.configuredServerName().isPresent();
    }

    private GeneralSettings mapToGeneralSettings(Map<String, Object> data) {
        GeneralSettings mapped = codec().decode(data);
        return GeneralSettings.builder()
            .homepageIconUrl(sanitizeOrEmpty(mapped.getHomepageIconUrl(), MAX_URL_LENGTH))
            .panelIconUrl(sanitizeOrEmpty(mapped.getPanelIconUrl(), MAX_URL_LENGTH))
            .defaultLanguage(resolveLanguage(mapped.getDefaultLanguage()))
            .build();
    }

    private String resolveLanguage(String value) {
        return SupportedLanguages.isSupported(value) ? value : SupportedLanguages.DEFAULT;
    }

    private SettingsCodec<GeneralSettings> codec() {
        return SettingsCodec.of(GeneralSettings.class, this::defaultGeneralSettings);
    }

    private String sanitizeOrEmpty(@Nullable String value, int maxLength) {
        return value != null ? sanitize(value, maxLength) : "";
    }

    private GeneralSettings defaultGeneralSettings() {
        return GeneralSettings.builder()
            .homepageIconUrl("")
            .panelIconUrl("")
            .defaultLanguage(SupportedLanguages.DEFAULT)
            .build();
    }

    private String sanitize(String value, int maxLength) {
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    public VersionedSettings<GeneralSettings> patchGeneralSettings(
        Server server,
        long expectedVersion,
        GeneralSettings patch
    ) {
        Optional<String> serverNameChange = requestedServerNameChange(server, patch.getServerDisplayName());
        Optional<String> publicUrlChange = requestedPublicUrlChange(patch.getPublicUrl());
        SavedIdentity saved = unitOfWork.write(() -> {
            VersionedSettings<GeneralSettings> settings = support.update(server, expectedVersion, data -> patched(data, patch));
            Server renamed = serverNameChange.map(instanceService::changeServerName).orElse(server);
            publicUrlChange.ifPresent(instanceService::changePublicUrl);
            return new SavedIdentity(renamed, settings);
        });
        return withInstanceIdentity(saved.server(), saved.settings());
    }

    private Map<String, Object> patched(Map<String, Object> data, GeneralSettings patch) {
        putIfNotNull(data, HOMEPAGE_ICON_URL, patch.getHomepageIconUrl(), MAX_URL_LENGTH);
        putIfNotNull(data, PANEL_ICON_URL, patch.getPanelIconUrl(), MAX_URL_LENGTH);
        putLanguageIfNotNull(data, patch.getDefaultLanguage());
        return data;
    }

    private Optional<String> requestedServerNameChange(Server server, @Nullable String requested) {
        if (requested == null) {
            return Optional.empty();
        }
        String name = ServerNames.normalize(requested).orElseThrow(() -> new ValidationException(BLANK_SERVER_NAME_MESSAGE));
        if (name.equals(server.getServerName())) {
            return Optional.empty();
        }
        if (isServerNameManaged()) {
            throw new ConflictException(MANAGED_SERVER_NAME_MESSAGE, SERVER_NAME_MANAGED_CODE);
        }
        return Optional.of(name);
    }

    private Optional<String> requestedPublicUrlChange(@Nullable String requested) {
        if (requested == null) {
            return Optional.empty();
        }
        HttpOrigin origin = HttpOrigin.parseExact(requested)
            .orElseThrow(() -> new ValidationException(INVALID_PUBLIC_URL_MESSAGE));
        if (publicUrls.knownOrigin().filter(origin::equals).isPresent()) {
            return Optional.empty();
        }
        if (publicUrls.isManagedByConfig()) {
            throw new ConflictException(MANAGED_PUBLIC_URL_MESSAGE, PUBLIC_URL_MANAGED_CODE);
        }
        return Optional.of(origin.value());
    }

    private void putIfNotNull(Map<String, Object> data, String key, @Nullable String value, int maxLength) {
        if (value != null) {
            data.put(key, sanitize(value, maxLength));
        }
    }

    private void putLanguageIfNotNull(Map<String, Object> data, @Nullable String value) {
        if (value == null) {
            return;
        }
        String language = value.trim();
        if (!SupportedLanguages.isSupported(language)) {
            throw new ValidationException("Unsupported default language: " + language);
        }
        data.put(DEFAULT_LANGUAGE, language);
    }

    private record SavedIdentity(Server server, VersionedSettings<GeneralSettings> settings) {
    }
}
