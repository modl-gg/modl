package gg.modl.minecraft.core.integration.mojang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Logger;

public final class SkinTextures {
    public static final String DEFAULT_SKIN = "default";

    private static final Logger logger = Logger.getLogger(SkinTextures.class.getName());
    private static final String TEXTURE_URL_PREFIX_HTTP = "http://textures.minecraft.net/texture/";
    private static final String TEXTURE_URL_PREFIX_HTTPS = "https://textures.minecraft.net/texture/";

    private SkinTextures() {}

    public static @Nullable String skinId(@Nullable String base64Texture) {
        if (base64Texture == null || base64Texture.trim().isEmpty()) return null;
        try {
            String decodedJson = new String(Base64.getDecoder().decode(base64Texture), StandardCharsets.UTF_8);
            JsonObject decodedObject = JsonParser.parseString(decodedJson).getAsJsonObject();
            if (!decodedObject.has("textures")) return null;

            JsonObject textures = decodedObject.getAsJsonObject("textures");
            if (!textures.has("SKIN")) return DEFAULT_SKIN;

            JsonObject skin = textures.getAsJsonObject("SKIN");
            if (!skin.has("url")) return null;

            return skin.get("url").getAsString()
                    .replace(TEXTURE_URL_PREFIX_HTTP, "")
                    .replace(TEXTURE_URL_PREFIX_HTTPS, "");
        } catch (RuntimeException e) {
            logger.fine("Could not read a skin id from a texture value: " + e.getMessage());
            return null;
        }
    }
}
