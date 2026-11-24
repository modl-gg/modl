package gg.modl.minecraft.spigot;

import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.PublicMethods;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Optional;

final class NativeSkinTextures implements SkinTextureSource {
    private static final String TEXTURES_PROPERTY_NAME = "textures";
    private static final String PLAYER_PROFILE_METHOD = "getPlayerProfile";
    private static final String PROFILE_PROPERTIES_METHOD = "getProperties";
    private static final String[] PROPERTY_NAME_ACCESSORS = {"getName", "name"};
    private static final String[] PROPERTY_VALUE_ACCESSORS = {"getValue", "value"};

    private final Method playerProfile;
    private final Method profileProperties;
    private final Method propertyName;
    private final Method propertyValue;

    private NativeSkinTextures(Method playerProfile, Method profileProperties, Method propertyName, Method propertyValue) {
        this.playerProfile = playerProfile;
        this.profileProperties = profileProperties;
        this.propertyName = propertyName;
        this.propertyValue = propertyValue;
    }

    static SkinTextureSource detect(Class<?> playerApiType, PluginLogger logger) {
        for (Method playerProfile : PublicMethods.withoutParameters(playerApiType, PLAYER_PROFILE_METHOD)) {
            for (Method profileProperties : PublicMethods.withoutParameters(playerProfile.getReturnType(), PROFILE_PROPERTIES_METHOD)) {
                Optional<NativeSkinTextures> textures = withPropertyAccessors(playerProfile, profileProperties);
                if (textures.isPresent()) return textures.get();
            }
        }
        logger.debug("This server has no player profile texture API; skin caching uses Mojang lookups");
        return SkinTextureSource.NONE;
    }

    @Override
    public @Nullable String texturesOf(Object player) throws ReflectiveOperationException {
        Object profile = playerProfile.invoke(player);
        if (profile == null) return null;
        Object properties = profileProperties.invoke(profile);
        if (!(properties instanceof Collection)) return null;
        for (Object property : (Collection<?>) properties) {
            if (TEXTURES_PROPERTY_NAME.equals(propertyName.invoke(property))) return (String) propertyValue.invoke(property);
        }
        return null;
    }

    private static Optional<NativeSkinTextures> withPropertyAccessors(Method playerProfile, Method profileProperties) {
        if (!Collection.class.isAssignableFrom(profileProperties.getReturnType())) return Optional.empty();
        Class<?> propertyType = elementTypeOf(profileProperties.getGenericReturnType());
        if (propertyType == null) return Optional.empty();
        Method name = stringAccessor(propertyType, PROPERTY_NAME_ACCESSORS);
        Method value = stringAccessor(propertyType, PROPERTY_VALUE_ACCESSORS);
        if (name == null || value == null) return Optional.empty();
        return Optional.of(new NativeSkinTextures(playerProfile, profileProperties, name, value));
    }

    private static @Nullable Class<?> elementTypeOf(Type collectionType) {
        if (!(collectionType instanceof ParameterizedType)) return null;
        Type[] arguments = ((ParameterizedType) collectionType).getActualTypeArguments();
        return arguments.length == 1 && arguments[0] instanceof Class ? (Class<?>) arguments[0] : null;
    }

    private static @Nullable Method stringAccessor(Class<?> type, String[] candidateNames) {
        for (String candidateName : candidateNames) {
            for (Method accessor : PublicMethods.withoutParameters(type, candidateName)) {
                if (accessor.getReturnType() == String.class) return accessor;
            }
        }
        return null;
    }
}
