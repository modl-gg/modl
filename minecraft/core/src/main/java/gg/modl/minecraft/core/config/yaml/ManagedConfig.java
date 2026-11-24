package gg.modl.minecraft.core.config.yaml;

import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class ManagedConfig {
    private final String fileName;
    private final ConfigSchema schema;

    private ManagedConfig(String fileName, ConfigSchema schema) {
        this.fileName = fileName;
        this.schema = schema;
    }

    public static ManagedConfig of(String fileName, ConfigSchema schema) {
        return new ManagedConfig(fileName, schema);
    }

    public String getFileName() {
        return fileName;
    }

    public String getResourcePath() {
        return "/" + fileName;
    }

    public ConfigSchema getSchema() {
        return schema;
    }

    public @Nullable String readPackagedDefault() {
        try (InputStream stream = ManagedConfig.class.getResourceAsStream(getResourcePath())) {
            if (stream == null) return null;
            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) builder.append(buffer, 0, read);
            }
            return builder.toString().replace("\r\n", "\n").replace("\r", "\n");
        } catch (IOException e) {
            return null;
        }
    }
}
