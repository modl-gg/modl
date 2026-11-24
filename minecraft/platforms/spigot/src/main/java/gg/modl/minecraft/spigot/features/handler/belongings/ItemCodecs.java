package gg.modl.minecraft.spigot.features.handler.belongings;

import gg.modl.minecraft.features.staffmode.custody.BelongingsException;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class ItemCodecs {
    private static final String EMPTY = "-";
    private static final char CODEC_SEPARATOR = ':';

    private final List<ItemCodec> codecs;
    private final ItemFidelity fidelity;

    ItemCodecs(List<ItemCodec> codecs, ItemFidelity fidelity) {
        this.codecs = codecs;
        this.fidelity = fidelity;
    }

    static ItemCodecs forThisServer() throws BelongingsException {
        try {
            NativeItems items = NativeItems.detect();
            List<ItemCodec> codecs = new ArrayList<>();
            PaperItemCodec.detect().ifPresent(codecs::add);
            NbtItemCodec.detect(items).ifPresent(codecs::add);
            codecs.add(new YamlItemCodec());
            return new ItemCodecs(codecs, items);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw ReflectiveCalls.failed("find this server's item format", failure);
        }
    }

    boolean areIdentical(@Nullable ItemStack[] expected, @Nullable ItemStack[] actual) {
        if (expected.length != actual.length) return false;
        for (int slot = 0; slot < expected.length; slot++) {
            if (!fidelity.areIdentical(expected[slot], actual[slot])) return false;
        }
        return true;
    }

    String encode(@Nullable ItemStack item) throws BelongingsException {
        if (item == null || isAir(item)) return EMPTY;
        BelongingsException firstFailure = null;
        for (ItemCodec codec : codecs) {
            try {
                byte[] bytes = codec.encode(item);
                ItemStack decoded = codec.decode(bytes);
                if (decoded != null && !isAir(decoded) && fidelity.areIdentical(item, decoded)) {
                    return codec.id() + CODEC_SEPARATOR + Base64.getEncoder().encodeToString(bytes);
                }
            } catch (BelongingsException failure) {
                if (firstFailure == null) firstFailure = failure;
            } catch (RuntimeException | LinkageError failure) {
                if (firstFailure == null) firstFailure = ReflectiveCalls.failed("check " + item.getType(), failure);
            }
        }
        throw new BelongingsException(item.getType() + " x" + item.getAmount() + " cannot be saved without losing data",
                firstFailure);
    }

    @Nullable ItemStack decode(String stored, boolean savedByThisVersion) throws BelongingsException {
        if (EMPTY.equals(stored)) return null;
        int separator = stored.indexOf(CODEC_SEPARATOR);
        if (separator < 1) throw new BelongingsException("An item entry is malformed");
        ItemCodec codec = codecNamed(stored.substring(0, separator));
        if (!savedByThisVersion && !codec.readsOtherVersions()) {
            throw new BelongingsException("Items saved as " + codec.id() + " can only be read by the Minecraft version that saved them");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(stored.substring(separator + 1));
        } catch (IllegalArgumentException failure) {
            throw ReflectiveCalls.failed("read an item entry", failure);
        }
        ItemStack item = codec.decode(bytes);
        if (item == null || isAir(item)) throw new BelongingsException("A saved item no longer exists on this server");
        return item;
    }

    private ItemCodec codecNamed(String id) throws BelongingsException {
        for (ItemCodec codec : codecs) {
            if (codec.id().equals(id)) return codec;
        }
        throw new BelongingsException("Items saved as " + id + " cannot be read by this server");
    }

    private static boolean isAir(ItemStack item) {
        return item.getType() == Material.AIR;
    }
}
