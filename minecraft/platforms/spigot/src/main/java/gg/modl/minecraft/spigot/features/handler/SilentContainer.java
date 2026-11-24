package gg.modl.minecraft.spigot.features.handler;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

final class SilentContainer implements InventoryHolder {
    private final Inventory inventory;

    private SilentContainer(int size, String title) {
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    static Inventory create(int size, String title) {
        return new SilentContainer(size, title).inventory;
    }

    static boolean isSilent(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof SilentContainer;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
