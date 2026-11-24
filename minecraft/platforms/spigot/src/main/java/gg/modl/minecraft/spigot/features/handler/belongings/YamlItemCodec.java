package gg.modl.minecraft.spigot.features.handler.belongings;

import gg.modl.minecraft.features.staffmode.custody.BelongingsException;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;

final class YamlItemCodec implements ItemCodec {
    private static final String KEY = "item";

    @Override
    public String id() {
        return "yaml";
    }

    @Override
    public boolean readsOtherVersions() {
        return false;
    }

    @Override
    public byte[] encode(ItemStack item) throws BelongingsException {
        try {
            YamlConfiguration document = new YamlConfiguration();
            document.set(KEY, item);
            return document.saveToString().getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException | LinkageError failure) {
            throw ReflectiveCalls.failed("serialize " + item.getType(), failure);
        }
    }

    @Override
    public ItemStack decode(byte[] bytes) throws BelongingsException {
        try {
            YamlConfiguration document = new YamlConfiguration();
            document.loadFromString(new String(bytes, StandardCharsets.UTF_8));
            ItemStack item = document.getItemStack(KEY);
            if (item == null) throw new BelongingsException("An item document holds no item");
            return item;
        } catch (InvalidConfigurationException | RuntimeException | LinkageError failure) {
            throw ReflectiveCalls.failed("read an item", failure);
        }
    }
}
