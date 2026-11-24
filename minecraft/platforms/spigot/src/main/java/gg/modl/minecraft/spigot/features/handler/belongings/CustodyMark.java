package gg.modl.minecraft.spigot.features.handler.belongings;

import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

interface CustodyMark {
    String kind();

    Optional<UUID> read(Player player);

    void write(Player player, UUID custody);

    void clear(Player player);
}
