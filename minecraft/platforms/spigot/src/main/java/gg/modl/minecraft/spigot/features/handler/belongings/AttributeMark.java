package gg.modl.minecraft.spigot.features.handler.belongings;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

final class AttributeMark implements CustodyMark {
    private static final String MODIFIER_NAME = "modl staff custody";
    private static final double NO_EFFECT = 0.0;

    private final Function<Player, Object> instanceOf;
    private final Method modifiers;
    private final Method add;
    private final Method remove;
    private final Method modifierName;
    private final Method modifierId;
    private final Constructor<?> newModifier;
    private final Object addOperation;

    private AttributeMark(Function<Player, Object> instanceOf, Method modifiers, Method add, Method remove,
                          Method modifierName, Method modifierId, Constructor<?> newModifier, Object addOperation) {
        this.instanceOf = instanceOf;
        this.modifiers = modifiers;
        this.add = add;
        this.remove = remove;
        this.modifierName = modifierName;
        this.modifierId = modifierId;
        this.newModifier = newModifier;
        this.addOperation = addOperation;
    }

    static Optional<CustodyMark> detect() {
        Optional<CustodyMark> bukkit = detectBukkitApi();
        return bukkit.isPresent() ? bukkit : detectMinecraft18();
    }

    private static Optional<CustodyMark> detectBukkitApi() {
        try {
            Class<?> attribute = Class.forName("org.bukkit.attribute.Attribute");
            Class<?> instance = Class.forName("org.bukkit.attribute.AttributeInstance");
            Class<?> modifier = Class.forName("org.bukkit.attribute.AttributeModifier");
            Class<?> operation = Class.forName("org.bukkit.attribute.AttributeModifier$Operation");
            Object knockbackResistance = attribute.getField("GENERIC_KNOCKBACK_RESISTANCE").get(null);
            Method getAttribute = Player.class.getMethod("getAttribute", attribute);
            return Optional.of(new AttributeMark(
                    player -> ReflectiveCalls.call(getAttribute, player, knockbackResistance),
                    instance.getMethod("getModifiers"),
                    instance.getMethod("addModifier", modifier),
                    instance.getMethod("removeModifier", modifier),
                    modifier.getMethod("getName"),
                    modifier.getMethod("getUniqueId"),
                    modifier.getConstructor(UUID.class, String.class, double.class, operation),
                    operation.getField("ADD_NUMBER").get(null)));
        } catch (ReflectiveOperationException beforeAttributeApi) {
            return Optional.empty();
        }
    }

    private static Optional<CustodyMark> detectMinecraft18() {
        String craftPackage = Bukkit.getServer().getClass().getPackage().getName();
        String revision = craftPackage.substring(craftPackage.lastIndexOf('.') + 1);
        if (!revision.startsWith("v1_8_")) return Optional.empty();
        String minecraft = "net.minecraft.server." + revision + ".";
        try {
            Class<?> attribute = Class.forName(minecraft + "IAttribute");
            Class<?> instance = Class.forName(minecraft + "AttributeInstance");
            Class<?> modifier = Class.forName(minecraft + "AttributeModifier");
            Object knockbackResistance = Class.forName(minecraft + "GenericAttributes").getField("c").get(null);
            Method getHandle = Class.forName(craftPackage + ".entity.CraftPlayer").getMethod("getHandle");
            Method getAttribute = Class.forName(minecraft + "EntityLiving").getMethod("getAttributeInstance", attribute);
            return Optional.of(new AttributeMark(
                    player -> ReflectiveCalls.call(getAttribute, ReflectiveCalls.call(getHandle, player), knockbackResistance),
                    instance.getMethod("c"),
                    instance.getMethod("b", modifier),
                    instance.getMethod("c", modifier),
                    modifier.getMethod("b"),
                    modifier.getMethod("a"),
                    modifier.getConstructor(UUID.class, String.class, double.class, int.class),
                    0));
        } catch (ReflectiveOperationException unsupported) {
            return Optional.empty();
        }
    }

    @Override
    public String kind() {
        return "attribute";
    }

    @Override
    public Optional<UUID> read(Player player) {
        List<Object> marks = marksOn(instanceOf.apply(player));
        return marks.isEmpty() ? Optional.empty() : Optional.of((UUID) ReflectiveCalls.call(modifierId, marks.get(0)));
    }

    @Override
    public void write(Player player, UUID custody) {
        Object instance = instanceOf.apply(player);
        removeMarks(instance);
        ReflectiveCalls.call(add, instance,
                ReflectiveCalls.create(newModifier, custody, MODIFIER_NAME, NO_EFFECT, addOperation));
    }

    @Override
    public void clear(Player player) {
        removeMarks(instanceOf.apply(player));
    }

    private void removeMarks(Object instance) {
        for (Object mark : marksOn(instance)) ReflectiveCalls.call(remove, instance, mark);
    }

    private List<Object> marksOn(Object instance) {
        List<Object> marks = new ArrayList<>();
        for (Object candidate : (Collection<?>) ReflectiveCalls.call(modifiers, instance)) {
            if (MODIFIER_NAME.equals(ReflectiveCalls.call(modifierName, candidate))) marks.add(candidate);
        }
        return marks;
    }
}
