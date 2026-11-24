package gg.modl.backend.update.launcher;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.update.DeploymentDetector;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

public record LauncherSettings(Path dataDirectory, String deployment) {
    private static final String APPLICATION_PROPERTIES = "application.properties";
    private static final String DATA_DIR = "modl.data-dir";

    public static LauncherSettings resolve(String[] args, Map<String, Object> dotenv) {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.addFirst(new SimpleCommandLinePropertySource(optionArguments(args)));
        sources.addFirst(new MapPropertySource("dotenv", new LinkedHashMap<>(dotenv)));
        sources.addLast(applicationProperties());
        String dataDirectory = environment.getProperty(DATA_DIR);
        String deployment = environment.getProperty(DeploymentDetector.PROPERTY, "");
        return new LauncherSettings(new DataDirectoryProperties(dataDirectory).root(), deployment);
    }

    private static String[] optionArguments(String[] args) {
        return Arrays.stream(args).filter(argument -> argument.startsWith("--")).toArray(String[]::new);
    }

    private static ResourcePropertySource applicationProperties() {
        try {
            return new ResourcePropertySource(new ClassPathResource(APPLICATION_PROPERTIES));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + APPLICATION_PROPERTIES, e);
        }
    }
}
