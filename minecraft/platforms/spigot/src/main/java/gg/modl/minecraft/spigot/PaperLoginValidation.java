package gg.modl.minecraft.spigot;

import gg.modl.minecraft.core.util.PluginLogger;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

final class PaperLoginValidation implements Listener {
    private static final String VALIDATE_LOGIN_EVENT = "io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent";
    private static final String LOGIN_CONNECTION = "io.papermc.paper.connection.PlayerLoginConnection";
    private static final String COMPONENT = "net.kyori.adventure.text.Component";
    private static final String UNIDENTIFIED_PLAYER = "a connecting player";
    private static final String LEGACY_SERIALIZER = "net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer";

    private final LoginAdmission admission;
    private final Class<? extends Event> eventType;
    private final Method connectionOf;
    private final Method isAllowed;
    private final Method kickMessage;
    private final Class<?> loginConnectionType;
    private final Method authenticatedProfile;
    private final Method profileId;
    private final Method profileName;
    private final Object legacySection;
    private final Method deserializeLegacy;

    private PaperLoginValidation(LoginAdmission admission, Class<? extends Event> eventType) throws ReflectiveOperationException {
        ClassLoader serverApi = eventType.getClassLoader();
        this.admission = admission;
        this.eventType = eventType;
        this.connectionOf = eventType.getMethod("getConnection");
        this.isAllowed = eventType.getMethod("isAllowed");
        this.kickMessage = eventType.getMethod("kickMessage", Class.forName(COMPONENT, false, serverApi));
        this.loginConnectionType = Class.forName(LOGIN_CONNECTION, false, serverApi);
        this.authenticatedProfile = loginConnectionType.getMethod("getAuthenticatedProfile");
        this.profileId = authenticatedProfile.getReturnType().getMethod("getId");
        this.profileName = authenticatedProfile.getReturnType().getMethod("getName");
        Class<?> legacySerializer = Class.forName(LEGACY_SERIALIZER, true, serverApi);
        this.legacySection = legacySerializer.getMethod("legacySection").invoke(null);
        this.deserializeLegacy = legacySerializer.getMethod("deserialize", String.class);
    }

    static Optional<PaperLoginValidation> detect(LoginAdmission admission, PluginLogger logger) {
        Class<? extends Event> eventType;
        try {
            eventType = Class.forName(VALIDATE_LOGIN_EVENT).asSubclass(Event.class);
        } catch (ClassNotFoundException serverWithoutConnectionValidation) {
            logger.debug("This server has no connection validation event; logins are validated on AsyncPlayerPreLoginEvent");
            return Optional.empty();
        }
        try {
            return Optional.of(new PaperLoginValidation(admission, eventType));
        } catch (ReflectiveOperationException | LinkageError changedApi) {
            logger.debug("This server's connection validation event lacks " + changedApi
                    + "; logins are validated on AsyncPlayerPreLoginEvent");
            return Optional.empty();
        }
    }

    void registerWith(JavaPlugin plugin) {
        plugin.getServer().getPluginManager().registerEvent(eventType, this, EventPriority.HIGHEST,
                (listener, event) -> validate(event), plugin);
    }

    void validate(Event event) throws EventException {
        if (!eventType.isInstance(event)) return;
        try {
            Object connection = connectionOf.invoke(event);
            if (!loginConnectionType.isInstance(connection)) return;
            LoginDenial denial = denialFor(event, connection);
            if (denial != null) kickMessage.invoke(event, deserializeLegacy.invoke(legacySection, denial.getMessage()));
        } catch (ReflectiveOperationException failure) {
            throw new EventException(failure);
        }
    }

    private @Nullable LoginDenial denialFor(Event event, Object connection) {
        try {
            Object profile = authenticatedProfile.invoke(connection);
            UUID id = profile == null ? null : (UUID) profileId.invoke(profile);
            String name = profile == null ? UNIDENTIFIED_PLAYER : String.valueOf(profileName.invoke(profile));
            return admission.admit(id, name, (Boolean) isAllowed.invoke(event));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return admission.failedCheck(UNIDENTIFIED_PLAYER, failure);
        }
    }
}
