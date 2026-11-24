package gg.modl.minecraft.spigot.features.handler.belongings;

import gg.modl.minecraft.features.staffmode.custody.BelongingsException;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;

final class NbtItemCodec implements ItemCodec {
    private final NativeItems items;
    private final Constructor<?> newCompound;
    private final Method saveToCompound;
    private final Function<Object, Object> loadFromCompound;

    private NbtItemCodec(NativeItems items, Constructor<?> newCompound, Method saveToCompound,
                         Function<Object, Object> loadFromCompound) {
        this.items = items;
        this.newCompound = newCompound;
        this.saveToCompound = saveToCompound;
        this.loadFromCompound = loadFromCompound;
    }

    static Optional<ItemCodec> detect(NativeItems items) {
        Class<?> compound = SpigotReflectionUtil.NMS_NBT_COMPOUND_CLASS;
        if (compound == null) return Optional.empty();
        Class<?> stack = items.stackType();
        Optional<Method> save = methodTaking(stack, compound, compound, false);
        if (!save.isPresent()) return Optional.empty();
        try {
            Constructor<?> newCompound = compound.getConstructor();
            Optional<Method> load = methodTaking(stack, compound, stack, true);
            Function<Object, Object> loader;
            if (load.isPresent()) {
                Method method = load.get();
                loader = tag -> ReflectiveCalls.call(method, null, tag);
            } else {
                Constructor<?> constructor = stack.getConstructor(compound);
                loader = tag -> ReflectiveCalls.create(constructor, tag);
            }
            return Optional.of(new NbtItemCodec(items, newCompound, save.get(), loader));
        } catch (NoSuchMethodException componentBasedServer) {
            return Optional.empty();
        }
    }

    private static Optional<Method> methodTaking(Class<?> owner, Class<?> parameter, Class<?> result, boolean isStatic) {
        return Arrays.stream(owner.getMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()) == isStatic)
                .filter(method -> method.getReturnType() == result)
                .filter(method -> Arrays.equals(method.getParameterTypes(), new Class<?>[]{parameter}))
                .findFirst();
    }

    @Override
    public String id() {
        return "nbt";
    }

    @Override
    public boolean readsOtherVersions() {
        return false;
    }

    @Override
    public byte[] encode(ItemStack item) throws BelongingsException {
        try {
            Object tag = ReflectiveCalls.call(saveToCompound, items.nativeCopy(item), ReflectiveCalls.create(newCompound));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            SpigotReflectionUtil.writeNmsNbtToStream(tag, new DataOutputStream(bytes));
            if (bytes.size() == 0) throw new BelongingsException("The NBT of " + item.getType() + " could not be written");
            return bytes.toByteArray();
        } catch (RuntimeException | LinkageError failure) {
            throw ReflectiveCalls.failed("serialize " + item.getType(), failure);
        }
    }

    @Override
    public @Nullable ItemStack decode(byte[] bytes) throws BelongingsException {
        try {
            Object tag = SpigotReflectionUtil.readNmsNbtFromStream(new DataInputStream(new ByteArrayInputStream(bytes)));
            if (tag == null) throw new BelongingsException("The NBT of an item could not be read");
            Object stack = loadFromCompound.apply(tag);
            return stack == null ? null : items.craftMirror(stack);
        } catch (RuntimeException | LinkageError failure) {
            throw ReflectiveCalls.failed("read an item", failure);
        }
    }
}
