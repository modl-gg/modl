package gg.modl.minecraft.spigot.features.handler.belongings;

import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

interface ItemFidelity {
    boolean areIdentical(@Nullable ItemStack expected, @Nullable ItemStack actual);
}
