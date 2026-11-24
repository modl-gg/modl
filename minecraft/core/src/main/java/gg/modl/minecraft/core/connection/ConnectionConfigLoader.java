package gg.modl.minecraft.core.connection;

import gg.modl.minecraft.core.boot.PlatformType;
import gg.modl.minecraft.core.config.yaml.CoreConfigBootstrap;
import gg.modl.minecraft.core.config.yaml.CoreManagedConfigs;
import gg.modl.minecraft.core.util.PluginLogger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ConnectionConfigLoader {
    private final Path dataFolder;
    private final PlatformType platform;
    private final ProxyForwarding forwarding;
    private final PluginLogger logger;

    public ConnectionConfigLoader(Path dataFolder, PlatformType platform, ProxyForwarding forwarding, PluginLogger logger) {
        this.dataFolder = dataFolder;
        this.platform = platform;
        this.forwarding = forwarding;
        this.logger = logger;
    }

    public ConnectionConfig load() {
        Path file = configFile();
        if (Files.exists(file)) {
            CoreConfigBootstrap.apply(CoreManagedConfigs.CONNECTION, dataFolder, logger);
        } else {
            create(file);
        }
        ConnectionConfig config = ConnectionConfigReader.read(readYaml(file, true));
        ConnectionConfigValidator.validate(config, platform);
        return config;
    }

    public String displayPath() {
        Path file = configFile().toAbsolutePath().normalize();
        Path workingDirectory = Paths.get("").toAbsolutePath().normalize();
        return file.startsWith(workingDirectory) ? workingDirectory.relativize(file).toString() : file.toString();
    }

    private Path configFile() {
        return dataFolder.resolve(ConnectionConfig.FILE_NAME);
    }

    private void create(Path file) {
        Path legacyBoot = dataFolder.resolve(LegacyBootConfig.FILE_NAME);
        boolean upgrading = Files.exists(legacyBoot);
        Map<String, Object> values = new LinkedHashMap<>();
        if (upgrading) {
            values.putAll(LegacyBootConfig.connectionValues(readYaml(legacyBoot, false), platform));
        } else if (forwarding.isEnabled()) {
            values.put(ConnectionYaml.MODE, ConnectionMode.BEHIND_PROXY);
        }

        write(file, ConnectionConfigTemplate.render(packagedTemplate(), values));

        if (upgrading) {
            retireLegacyBootConfig(legacyBoot);
            logFreshInstanceNotice(values.get(ConnectionYaml.MODE) == ConnectionMode.BEHIND_PROXY);
        } else if (forwarding.isEnabled()) {
            logger.info("Proxy forwarding is enabled on this server (" + forwarding.source()
                    + "), so " + displayPath() + " was created with mode: " + ConnectionMode.BEHIND_PROXY.yamlName() + ".");
        }
    }

    private void retireLegacyBootConfig(Path legacyBoot) {
        try {
            Files.move(legacyBoot, legacyBoot.resolveSibling(LegacyBootConfig.RENAMED_FILE_NAME),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.warning("Could not rename " + LegacyBootConfig.FILE_NAME + " to " + LegacyBootConfig.RENAMED_FILE_NAME
                    + ": " + e.getMessage());
        }
    }

    private void logFreshInstanceNotice(boolean behindProxy) {
        logger.warning("====================================================================");
        logger.warning("modl is now self-hosted. " + LegacyBootConfig.FILE_NAME + " was converted to "
                + displayPath() + " and renamed to " + LegacyBootConfig.RENAMED_FILE_NAME + ".");
        logger.warning("This is a fresh instance: data from your former modl.gg panel is not loaded.");
        if (behindProxy) {
            logger.warning("Set remote.url to the address of your new panel and remote.api-key to a server key from "
                    + PanelLocations.ADD_SERVER + ".");
        }
        logger.warning("====================================================================");
    }

    private String packagedTemplate() {
        String template = CoreManagedConfigs.CONNECTION.readPackagedDefault();
        if (template == null) throw new ConnectionConfigException("The packaged " + ConnectionConfig.FILE_NAME + " is missing from the plugin jar.");
        return template;
    }

    private void write(Path file, String content) {
        try {
            Files.createDirectories(dataFolder);
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ConnectionConfigException("Could not write " + displayPath() + ": " + e.getMessage(), e);
        }
    }

    private Map<?, ?> readYaml(Path file, boolean required) {
        Object root;
        try (InputStream input = Files.newInputStream(file)) {
            root = new Yaml().load(input);
        } catch (IOException | RuntimeException e) {
            if (required) throw new ConnectionConfigException(file.getFileName() + " could not be read: " + e.getMessage(), e);
            logger.warning("Ignoring unreadable " + file.getFileName() + ": " + e.getMessage());
            return Collections.emptyMap();
        }
        if (root instanceof Map) return (Map<?, ?>) root;
        if (required) throw new ConnectionConfigException(file.getFileName() + " must contain YAML keys and values.");
        return Collections.emptyMap();
    }
}
