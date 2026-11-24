package gg.modl.minecraft.spigot.features.handler.belongings;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

final class NativeItems implements ItemFidelity {
    private final Class<?> stackType;
    private final Method asNativeCopy;
    private final Method asCraftMirror;
    private final List<Method> comparisons;

    private NativeItems(Class<?> stackType, Method asNativeCopy, Method asCraftMirror) {
        this.stackType = stackType;
        this.asNativeCopy = asNativeCopy;
        this.asCraftMirror = asCraftMirror;
        this.comparisons = Arrays.stream(stackType.getMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()) && method.getReturnType() == boolean.class)
                .filter(method -> Arrays.equals(method.getParameterTypes(), new Class<?>[]{stackType, stackType}))
                .collect(Collectors.toList());
        if (comparisons.isEmpty()) throw new IllegalStateException("This server has no way to compare items");
    }

    static NativeItems detect() throws ReflectiveOperationException {
        String craftPackage = Bukkit.getServer().getClass().getPackage().getName();
        Class<?> craftItemStack = Class.forName(craftPackage + ".inventory.CraftItemStack");
        Method asNativeCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
        Class<?> stackType = asNativeCopy.getReturnType();
        return new NativeItems(stackType, asNativeCopy, craftItemStack.getMethod("asCraftMirror", stackType));
    }

    Class<?> stackType() {
        return stackType;
    }

    Object nativeCopy(ItemStack item) {
        return ReflectiveCalls.call(asNativeCopy, null, item);
    }

    ItemStack craftMirror(Object nativeStack) {
        return (ItemStack) ReflectiveCalls.call(asCraftMirror, null, nativeStack);
    }

    @Override
    public boolean areIdentical(@Nullable ItemStack expected, @Nullable ItemStack actual) {
        if (expected == null || actual == null || isAir(expected) || isAir(actual)) {
            return isEmpty(expected) && isEmpty(actual);
        }
        if (expected.getAmount() != actual.getAmount()) return false;
        Object original = nativeCopy(expected);
        Object identicalCopy = nativeCopy(expected);
        Object candidate = nativeCopy(actual);
        for (Method comparison : comparisons) {
            if (!ReflectiveCalls.call(comparison, null, original, candidate)
                    .equals(ReflectiveCalls.call(comparison, null, original, identicalCopy))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEmpty(@Nullable ItemStack item) {
        return item == null || isAir(item);
    }

    private static boolean isAir(ItemStack item) {
        return item.getType() == Material.AIR;
    }
}
