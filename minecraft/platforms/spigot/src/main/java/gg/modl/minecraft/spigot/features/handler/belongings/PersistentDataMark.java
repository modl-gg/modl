package gg.modl.minecraft.spigot.features.handler.belongings;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

final class PersistentDataMark implements CustodyMark {
    private static final String KEY = "staff_custody";

    private final Method getContainer;
    private final Method get;
    private final Method set;
    private final Method remove;
    private final Object key;
    private final Object stringType;

    private PersistentDataMark(Method getContainer, Method get, Method set, Method remove, Object key, Object stringType) {
        this.getContainer = getContainer;
        this.get = get;
        this.set = set;
        this.remove = remove;
        this.key = key;
        this.stringType = stringType;
    }

    static Optional<CustodyMark> detect(Plugin plugin) {
        try {
            Method getContainer = Player.class.getMethod("getPersistentDataContainer");
            Class<?> keyType = Class.forName("org.bukkit.NamespacedKey");
            Class<?> dataType = Class.forName("org.bukkit.persistence.PersistentDataType");
            Class<?> container = Class.forName("org.bukkit.persistence.PersistentDataContainer");
            return Optional.of(new PersistentDataMark(getContainer,
                    container.getMethod("get", keyType, dataType),
                    container.getMethod("set", keyType, dataType, Object.class),
                    container.getMethod("remove", keyType),
                    keyType.getConstructor(Plugin.class, String.class).newInstance(plugin, KEY),
                    dataType.getField("STRING").get(null)));
        } catch (ReflectiveOperationException beforePersistentData) {
            return Optional.empty();
        }
    }

    @Override
    public String kind() {
        return "persistent-data";
    }

    @Override
    public Optional<UUID> read(Player player) {
        Object value = ReflectiveCalls.call(get, ReflectiveCalls.call(getContainer, player), key, stringType);
        return value == null ? Optional.empty() : Optional.of(UUID.fromString((String) value));
    }

    @Override
    public void write(Player player, UUID custody) {
        ReflectiveCalls.call(set, ReflectiveCalls.call(getContainer, player), key, stringType, custody.toString());
    }

    @Override
    public void clear(Player player) {
        ReflectiveCalls.call(remove, ReflectiveCalls.call(getContainer, player), key);
    }
}
