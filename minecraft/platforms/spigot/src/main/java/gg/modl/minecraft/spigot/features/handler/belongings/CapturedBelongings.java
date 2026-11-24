package gg.modl.minecraft.spigot.features.handler.belongings;

import gg.modl.minecraft.features.staffmode.custody.Belongings;
import lombok.AccessLevel;
import lombok.Getter;
import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

@Getter(AccessLevel.PACKAGE)
final class CapturedBelongings implements Belongings {
    private final @Nullable ItemStack[] contents;
    private final @Nullable ItemStack[] armor;
    private final GameMode mode;
    private final Vitals vitals;
    @Getter(AccessLevel.NONE)
    private final String encoded;

    CapturedBelongings(@Nullable ItemStack[] contents, @Nullable ItemStack[] armor, GameMode mode, Vitals vitals,
                       String encoded) {
        this.contents = contents;
        this.armor = armor;
        this.mode = mode;
        this.vitals = vitals;
        this.encoded = encoded;
    }

    @Override
    public String encoded() {
        return encoded;
    }

    @Override
    public String gameMode() {
        return mode.name();
    }
}
