package gg.modl.minecraft.replay.recording;

import lombok.AccessLevel;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.util.List;

@Getter
final class ModelTarget {
    static final int INVENTORY_SLOTS = 46;

    private float health = 20F;
    private float maxHealth = 20F;
    private int food = 20;
    private float saturation = 5F;
    private int armor;
    private int xpLevel;
    private float xpProgress;
    private int xpTotal;
    private int selectedSlot;
    private int gameMode;
    @Getter(AccessLevel.NONE)
    private final @Nullable ModelItem[] inventory = new ModelItem[INVENTORY_SLOTS];

    ModelTarget copy() {
        ModelTarget copy = new ModelTarget();
        copy.health = health;
        copy.maxHealth = maxHealth;
        copy.food = food;
        copy.saturation = saturation;
        copy.armor = armor;
        copy.xpLevel = xpLevel;
        copy.xpProgress = xpProgress;
        copy.xpTotal = xpTotal;
        copy.selectedSlot = selectedSlot;
        copy.gameMode = gameMode;
        System.arraycopy(inventory, 0, copy.inventory, 0, INVENTORY_SLOTS);
        return copy;
    }

    void health(float newHealth, int newFood, float newSaturation) {
        health = newHealth;
        food = newFood;
        saturation = newSaturation;
    }

    void attributes(@Nullable Float newMaxHealth, @Nullable Integer newArmor) {
        if (newMaxHealth != null) maxHealth = newMaxHealth;
        if (newArmor != null) armor = newArmor;
    }

    void experience(float progress, int level, int total) {
        xpProgress = progress;
        xpLevel = level;
        xpTotal = total;
    }

    void select(int slot) {
        selectedSlot = slot;
    }

    void gameMode(int mode) {
        gameMode = mode;
    }

    void inventory(boolean replaceAll, List<SlotItem> slots) {
        if (replaceAll) {
            for (int i = 0; i < INVENTORY_SLOTS; i++) inventory[i] = null;
        }
        for (SlotItem slot : slots) {
            if (slot.getSlot() >= 0 && slot.getSlot() < INVENTORY_SLOTS) inventory[slot.getSlot()] = slot.getItem();
        }
    }

    @Nullable ModelItem slot(int index) {
        return inventory[index];
    }
}
