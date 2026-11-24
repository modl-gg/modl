package gg.modl.backend.settings.service;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.instance.InstanceService;
import gg.modl.backend.instance.PanelLocations;
import gg.modl.backend.server.data.Server;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public class ApiKeySettingsService {
    private static final String API_KEY_PREFIX = "modl_";
    private static final String MANAGED_BY_CONFIG_MESSAGE =
        "The API key is set by MODL_API_KEY and cannot be changed from the panel";

    private final IdGenerator idGenerator;
    private final InstanceProperties instanceProperties;
    private final InstanceService instanceService;
    private final OperatorNotice operatorNotice;

    public ApiKeySettingsService(
        IdGenerator idGenerator,
        InstanceProperties instanceProperties,
        InstanceService instanceService,
        OperatorNotice operatorNotice
    ) {
        this.idGenerator = idGenerator;
        this.instanceProperties = instanceProperties;
        this.instanceService = instanceService;
        this.operatorNotice = operatorNotice;
    }

    public void ensureInstanceKey(Server server) {
        Optional<String> configuredKey = instanceProperties.configuredApiKey();
        if (configuredKey.isPresent()) {
            storeIfChanged(server, configuredKey.get());
        } else if (!isPresent(server.getApiKey())) {
            generateAndAnnounce();
        }
    }

    public boolean isManagedByConfig() {
        return instanceProperties.configuredApiKey().isPresent();
    }

    public String generateApiKey(Server server) {
        if (isManagedByConfig()) {
            throw new ConflictException(MANAGED_BY_CONFIG_MESSAGE);
        }
        String apiKey = generateSecureApiKey();
        instanceService.changeApiKey(apiKey);
        return apiKey;
    }

    public boolean hasApiKey(Server server) {
        return isPresent(server.getApiKey());
    }

    @Nullable
    public String revealApiKey(Server server) {
        return isPresent(server.getApiKey()) ? server.getApiKey() : null;
    }

    private void generateAndAnnounce() {
        instanceService.changeApiKey(generateSecureApiKey());
        operatorNotice.publish("API key generated. View it in " + PanelLocations.MINECRAFT_SERVERS + ".");
    }

    private void storeIfChanged(Server server, String apiKey) {
        if (!apiKey.equals(server.getApiKey())) {
            instanceService.changeApiKey(apiKey);
        }
    }

    private String generateSecureApiKey() {
        return API_KEY_PREFIX + idGenerator.generateToken();
    }

    private static boolean isPresent(@Nullable String apiKey) {
        return apiKey != null && !apiKey.isBlank();
    }
}
