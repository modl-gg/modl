package gg.modl.minecraft.spigot.features;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

interface EntityStates {
    @Nullable UserProfile profile(Player player);

    @Nullable EntityType type(Entity entity);

    List<EntityData<?>> metadata(Entity entity);

    Map<EquipmentSlot, ItemStack> equipment(Entity entity);

    int trackingRange(Entity entity);

    int viewDistanceBlocks(Player viewer);
}
