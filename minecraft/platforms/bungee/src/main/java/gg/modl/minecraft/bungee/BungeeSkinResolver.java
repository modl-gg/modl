package gg.modl.minecraft.bungee;

import gg.modl.minecraft.core.util.PluginLogger;
import lombok.RequiredArgsConstructor;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;

final class BungeeSkinResolver {
    private static final String TEXTURES_PROPERTY = "textures";

    private final PluginLogger logger;

    private volatile @Nullable ProfileAccessors accessors;

    BungeeSkinResolver(PluginLogger logger) {
        this.logger = logger;
    }

    @Nullable String resolveTexture(ProxiedPlayer player) {
        return resolveTexture(player.getPendingConnection());
    }

    @Nullable String resolveTexture(PendingConnection pendingConnection) {
        ProfileAccessors current = accessors;
        if (current == null) {
            current = discoverAccessors(pendingConnection);
            if (current == null) return null;
            accessors = current;
        }

        try {
            return current.readTexture(pendingConnection);
        } catch (Exception e) {
            logger.debug("Skin texture lookup failed: " + e.getMessage());
            return null;
        }
    }

    private @Nullable ProfileAccessors discoverAccessors(Object pendingConnection) {
        try {
            Method loginProfile = pendingConnection.getClass().getMethod("getLoginProfile");
            Object profile = loginProfile.invoke(pendingConnection);
            if (profile == null) return null;

            Method properties = profile.getClass().getMethod("getProperties");
            Object @Nullable [] resolvedProperties = (Object[]) properties.invoke(profile);
            if (resolvedProperties == null || resolvedProperties.length == 0) return null;

            Class<?> propertyType = resolvedProperties[0].getClass();
            return new ProfileAccessors(loginProfile, properties,
                    propertyType.getMethod("getName"), propertyType.getMethod("getValue"));
        } catch (Exception e) {
            logger.debug("Failed to resolve skin reflection methods: " + e.getMessage());
            return null;
        }
    }

    @RequiredArgsConstructor
    private static final class ProfileAccessors {
        private final Method loginProfile;
        private final Method properties;
        private final Method propertyName;
        private final Method propertyValue;

        @Nullable String readTexture(Object pendingConnection) throws Exception {
            Object profile = loginProfile.invoke(pendingConnection);
            if (profile == null) return null;

            Object @Nullable [] resolvedProperties = (Object[]) properties.invoke(profile);
            if (resolvedProperties == null) return null;

            for (Object property : resolvedProperties) {
                if (TEXTURES_PROPERTY.equals(propertyName.invoke(property))) {
                    return (String) propertyValue.invoke(property);
                }
            }
            return null;
        }
    }
}
