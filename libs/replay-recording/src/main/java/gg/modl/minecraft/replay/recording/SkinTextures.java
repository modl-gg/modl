package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

final class SkinTextures {
    static final Skin NONE = new Skin("", false);

    private static final int MAX_PROPERTY_CHARS = 16384;
    private static final String TEXTURE_HOST_PATH = "/texture/";

    private SkinTextures() {
    }

    static Skin of(@Nullable UserProfile profile) {
        if (profile == null) return NONE;
        List<TextureProperty> properties = profile.getTextureProperties();
        if (properties == null) return NONE;
        for (TextureProperty property : properties) {
            if ("textures".equals(property.getName())) return parse(property.getValue());
        }
        return NONE;
    }

    static Skin parse(@Nullable String base64) {
        if (base64 == null || base64.isEmpty() || base64.length() > MAX_PROPERTY_CHARS) return NONE;
        try {
            String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject textures = object(root, "textures");
            JsonObject skin = textures == null ? null : object(textures, "SKIN");
            if (skin == null) return NONE;
            JsonElement url = skin.get("url");
            if (url == null || !url.isJsonPrimitive()) return NONE;
            String hash = hashOf(url.getAsString());
            JsonObject metadata = object(skin, "metadata");
            JsonElement model = metadata == null ? null : metadata.get("model");
            boolean slim = model != null && model.isJsonPrimitive() && "slim".equals(model.getAsString());
            return hash.isEmpty() ? NONE : new Skin(hash, slim);
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    static String hashOf(String url) {
        int index = url.lastIndexOf(TEXTURE_HOST_PATH);
        if (index < 0) return "";
        String hash = url.substring(index + TEXTURE_HOST_PATH.length());
        for (int i = 0; i < hash.length(); i++) {
            char c = hash.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return "";
        }
        return hash.length() <= 128 ? hash : "";
    }

    private static @Nullable JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    @Value
    static class Skin {
        String hash;
        boolean slim;
    }
}
