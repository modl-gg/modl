package gg.modl.backend.cli;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.selection.DatabaseSelection;
import gg.modl.backend.database.selection.DatabaseSelectionConfiguration;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import gg.modl.backend.storage.StorageProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

record CliSettings(
    SqlDatabaseProperties configuredDatabase,
    DataDirectoryProperties dataDirectory,
    StorageProperties storage,
    PropertySources variables
) {
    private static final String DOTENV_SOURCE = "dotenv";
    private static final String APPLICATION_PROPERTIES = "application.properties";
    private static final String DATA_DIRECTORY_PREFIX = "modl";
    private static final String STORAGE_PREFIX = "modl.storage";

    static CliSettings resolve(Map<String, Object> dotenv) {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.addFirst(new MapPropertySource(DOTENV_SOURCE, dotenv));
        sources.addLast(applicationProperties());
        Binder binder = Binder.get(environment);
        DataDirectoryProperties dataDirectory = binder.bind(DATA_DIRECTORY_PREFIX, DataDirectoryProperties.class)
            .orElseGet(() -> new DataDirectoryProperties(null));
        return new CliSettings(
            DatabaseSelectionConfiguration.configured(binder),
            dataDirectory,
            binder.bind(STORAGE_PREFIX, StorageProperties.class).orElseGet(() -> new StorageProperties(null, StorageProperties.S3.EMPTY)),
            sources
        );
    }

    SqlDatabaseProperties database() {
        return DatabaseSelection.forTools(configuredDatabase, dataDirectory);
    }

    Optional<String> variable(String name) {
        return variables.stream()
            .map(source -> source.getProperty(name))
            .filter(Objects::nonNull)
            .map(Object::toString)
            .filter(value -> !value.isBlank())
            .findFirst();
    }

    private static ResourcePropertySource applicationProperties() {
        try {
            return new ResourcePropertySource(new ClassPathResource(APPLICATION_PROPERTIES));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + APPLICATION_PROPERTIES, e);
        }
    }
}
