package gg.modl.minecraft.spigot.features;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class PacketEventsEntityStates implements EntityStates {
    private final SpigotTrackingRanges trackingRanges;

    PacketEventsEntityStates(SpigotTrackingRanges trackingRanges) {
        this.trackingRanges = trackingRanges;
    }

    @Override
    public int trackingRange(Entity entity) {
        return trackingRanges.rangeOf(entity);
    }

    @Override
    public int viewDistanceBlocks(Player viewer) {
        return trackingRanges.viewDistanceBlocks(viewer);
    }

    @Override
    public @Nullable UserProfile profile(Player player) {
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        return user == null ? null : user.getProfile();
    }

    @Override
    public @Nullable EntityType type(Entity entity) {
        return SpigotConversionUtil.fromBukkitEntityType(entity.getType());
    }

    @Override
    public List<EntityData<?>> metadata(Entity entity) {
        try {
            return SpigotConversionUtil.getEntityMetadata(entity);
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public Map<EquipmentSlot, ItemStack> equipment(Entity entity) {
        if (!(entity instanceof LivingEntity)) return Collections.emptyMap();
        EntityEquipment equipment = ((LivingEntity) entity).getEquipment();
        if (equipment == null) return Collections.emptyMap();
        Map<EquipmentSlot, ItemStack> items = new EnumMap<>(EquipmentSlot.class);
        put(items, EquipmentSlot.MAIN_HAND, equipment.getItemInHand());
        put(items, EquipmentSlot.HELMET, equipment.getHelmet());
        put(items, EquipmentSlot.CHEST_PLATE, equipment.getChestplate());
        put(items, EquipmentSlot.LEGGINGS, equipment.getLeggings());
        put(items, EquipmentSlot.BOOTS, equipment.getBoots());
        return items;
    }

    private static void put(Map<EquipmentSlot, ItemStack> items, EquipmentSlot slot, org.bukkit.inventory.@Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return;
        items.put(slot, SpigotConversionUtil.fromBukkitItemStack(item));
    }
}
