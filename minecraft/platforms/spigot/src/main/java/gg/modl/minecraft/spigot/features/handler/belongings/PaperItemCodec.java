package gg.modl.minecraft.spigot.features.handler.belongings;

import gg.modl.minecraft.features.staffmode.custody.BelongingsException;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Optional;

final class PaperItemCodec implements ItemCodec {
    private final Method serializeAsBytes;
    private final Method deserializeBytes;

    private PaperItemCodec(Method serializeAsBytes, Method deserializeBytes) {
        this.serializeAsBytes = serializeAsBytes;
        this.deserializeBytes = deserializeBytes;
    }

    static Optional<ItemCodec> detect() {
        try {
            return Optional.of(new PaperItemCodec(ItemStack.class.getMethod("serializeAsBytes"),
                    ItemStack.class.getMethod("deserializeBytes", byte[].class)));
        } catch (NoSuchMethodException notPaper) {
            return Optional.empty();
        }
    }

    @Override
    public String id() {
        return "paper";
    }

    @Override
    public boolean readsOtherVersions() {
        return true;
    }

    @Override
    public byte[] encode(ItemStack item) throws BelongingsException {
        try {
            return (byte[]) ReflectiveCalls.call(serializeAsBytes, item);
        } catch (RuntimeException | LinkageError failure) {
            throw ReflectiveCalls.failed("serialize " + item.getType(), failure);
        }
    }

    @Override
    public @Nullable ItemStack decode(byte[] bytes) throws BelongingsException {
        try {
            return (ItemStack) ReflectiveCalls.call(deserializeBytes, null, (Object) bytes);
        } catch (RuntimeException | LinkageError failure) {
            throw ReflectiveCalls.failed("read an item", failure);
        }
    }
}
