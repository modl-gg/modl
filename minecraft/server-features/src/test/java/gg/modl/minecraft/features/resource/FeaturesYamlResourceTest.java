package gg.modl.minecraft.features.resource;

import gg.modl.minecraft.features.FeaturePlayerProvider;
import gg.modl.minecraft.features.FeaturesPluginContext;
import gg.modl.minecraft.features.config.ServerFeaturesManagedConfigs;
import gg.modl.minecraft.features.FeatureScheduler;
import gg.modl.minecraft.core.util.PluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeaturesYamlResourceTest {

    @TempDir
    Path tempDir;

    @Test
    void ensureDefaultFileCopiesMissingPackagedResource() throws IOException {
        FeaturesYamlResource.ensureDefaultFile(new TestContext(tempDir), ServerFeaturesManagedConfigs.STAFF_MODE, testLogger());

        Path copiedFile = tempDir.resolve("staff_mode.yml");
        assertTrue(Files.exists(copiedFile));
        assertEquals(resourceText("staff_mode.yml"), new String(Files.readAllBytes(copiedFile), StandardCharsets.UTF_8));
    }

    @Test
    void loadResourceMapLoadsPackagedResource() throws IOException {
        assertTrue(FeaturesYamlResource.loadResourceMap(getClass(), "/staff_mode.yml").size() > 0);
    }

    private static String resourceText(String resourcePath) throws IOException {
        try (InputStream input = FeaturesYamlResourceTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("Missing resource " + resourcePath);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private PluginLogger testLogger() {
        return PluginLogger.fromJul(Logger.getLogger("test"));
    }

    private static class TestContext implements FeaturesPluginContext {
        private final Path dataFolder;

        private TestContext(Path dataFolder) {
            this.dataFolder = dataFolder;
        }

        @Override
        public FeatureScheduler getScheduler() {
            return null;
        }

        @Override
        public FeaturePlayerProvider getPlayerProvider() {
            return null;
        }

        @Override
        public Path getDataFolder() {
            return dataFolder;
        }

        @Override
        public void saveDefaultResource(String resourcePath) {
            try {
                Path target = dataFolder.resolve(resourcePath);
                Files.createDirectories(target.getParent());
                try (InputStream input = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                    if (input != null) {
                        Files.copy(input, target);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String getMinecraftVersion() {
            return "test";
        }
    }
}
