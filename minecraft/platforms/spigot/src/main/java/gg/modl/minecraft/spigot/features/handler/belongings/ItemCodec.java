package gg.modl.minecraft.spigot.features.handler.belongings;

import gg.modl.minecraft.features.staffmode.custody.BelongingsException;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

interface ItemCodec {
    String id();

    boolean readsOtherVersions();

    byte[] encode(ItemStack item) throws BelongingsException;

    @Nullable ItemStack decode(byte[] bytes) throws BelongingsException;
}
