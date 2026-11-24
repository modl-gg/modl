package gg.modl.minecraft.spigot;

import gg.modl.minecraft.core.util.PluginLogger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeSkinTexturesTest {
    private static final String TEXTURES = "dGV4dHVyZXM=";

    private final RecordingLogger logger = new RecordingLogger();

    public interface PaperPlayerApi {
        PaperProfileApi getPlayerProfile();
    }

    public interface PaperProfileApi {
        Set<ProfileProperty> getProperties();
    }

    public static final class ProfileProperty {
        private final String name;
        private final String value;

        ProfileProperty(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }
    }

    public interface RecordStylePlayerApi {
        RecordStyleProfileApi getPlayerProfile();
    }

    public interface RecordStyleProfileApi {
        List<RecordStyleProperty> getProperties();
    }

    public static final class RecordStyleProperty {
        private final String name;
        private final String value;

        RecordStyleProperty(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public String name() {
            return name;
        }

        public String value() {
            return value;
        }
    }

    public interface SpigotPlayerApi {
        SpigotProfileApi getPlayerProfile();
    }

    public interface SpigotProfileApi {
        Object getTextures();
    }

    public interface LegacyPlayerApi {
        String getName();
    }

    @Test
    void readsTheTexturesPropertyThroughPapersProfileApi() throws ReflectiveOperationException {
        Set<ProfileProperty> properties = new LinkedHashSet<>(Arrays.asList(
                new ProfileProperty("other", "ignored"), new ProfileProperty("textures", TEXTURES)));
        PaperPlayerApi player = () -> () -> properties;

        assertEquals(TEXTURES, detect(PaperPlayerApi.class).texturesOf(player));
    }

    @Test
    void readsRecordStylePropertyAccessors() throws ReflectiveOperationException {
        RecordStylePlayerApi player = () -> () -> Arrays.asList(new RecordStyleProperty("textures", TEXTURES));

        assertEquals(TEXTURES, detect(RecordStylePlayerApi.class).texturesOf(player));
    }

    @Test
    void aPlayerWithoutTheTexturesPropertyHasNoNativeTextures() throws ReflectiveOperationException {
        PaperPlayerApi player = () -> () -> new LinkedHashSet<>(Arrays.asList(new ProfileProperty("other", "ignored")));

        assertNull(detect(PaperPlayerApi.class).texturesOf(player));
    }

    @Test
    void spigotsProfileApiWithoutPropertiesFallsBackSilently() throws ReflectiveOperationException {
        assertFallsBack(SpigotPlayerApi.class);
    }

    @Test
    void legacyServersWithoutPlayerProfilesAreDetectedWithoutProbingAPlayer() throws ReflectiveOperationException {
        assertFallsBack(LegacyPlayerApi.class);
    }

    private void assertFallsBack(Class<?> playerApiType) throws ReflectiveOperationException {
        SkinTextureSource textures = NativeSkinTextures.detect(playerApiType, logger);

        assertSame(SkinTextureSource.NONE, textures);
        assertNull(textures.texturesOf(new Object()));
        assertTrue(logger.console.isEmpty(), logger.console.toString());
        assertEquals(Collections.singletonList("This server has no player profile texture API; skin caching uses Mojang lookups"),
                logger.debug);
    }

    private SkinTextureSource detect(Class<?> playerApiType) {
        SkinTextureSource textures = NativeSkinTextures.detect(playerApiType, logger);
        assertNotSame(SkinTextureSource.NONE, textures, "no native skin textures on " + playerApiType.getSimpleName());
        assertTrue(logger.debug.isEmpty(), logger.debug.toString());
        return textures;
    }

    private static final class RecordingLogger implements PluginLogger {
        private final List<String> console = new ArrayList<>();
        private final List<String> debug = new ArrayList<>();

        @Override
        public void info(String message) {
            console.add(message);
        }

        @Override
        public void warning(String message) {
            console.add(message);
        }

        @Override
        public void severe(String message) {
            console.add(message);
        }

        @Override
        public void debug(String message) {
            debug.add(message);
        }
    }
}
