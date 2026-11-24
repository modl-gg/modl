package dev.simplix.cirrus.common.packet;

import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import lombok.Value;
import lombok.experimental.UtilityClass;

@UtilityClass
public class LegacyItemMapping {

    @Value
    private static class LegacyEntry {
        String typeIdentifier;
        int dataValue;
    }

    private final Map<String, LegacyEntry> MAPPINGS = new HashMap<>();
    private final Map<String, String> OLDER_EQUIVALENTS = new HashMap<>();
    private final Set<String> REPORTED_FALLBACKS = ConcurrentHashMap.newKeySet();

    static {
        // Skulls: all variants map to skeleton_skull (legacy ID 397) with different data values
        put("minecraft:skeleton_skull", 0);
        put("minecraft:wither_skeleton_skull", "minecraft:skeleton_skull", 1);
        put("minecraft:zombie_head", "minecraft:skeleton_skull", 2);
        put("minecraft:player_head", "minecraft:skeleton_skull", 3);
        put("minecraft:creeper_head", "minecraft:skeleton_skull", 4);
        put("minecraft:dragon_head", "minecraft:skeleton_skull", 5);

        // Fish: all variants map to cod (legacy ID 349)
        put("minecraft:cod", 0);
        put("minecraft:salmon", "minecraft:cod", 1);
        put("minecraft:tropical_fish", "minecraft:cod", 2);
        put("minecraft:pufferfish", "minecraft:cod", 3);

        // Wool: all variants map to white_wool (legacy ID 35)
        put("minecraft:white_wool", 0);
        put("minecraft:orange_wool", "minecraft:white_wool", 1);
        put("minecraft:magenta_wool", "minecraft:white_wool", 2);
        put("minecraft:light_blue_wool", "minecraft:white_wool", 3);
        put("minecraft:yellow_wool", "minecraft:white_wool", 4);
        put("minecraft:lime_wool", "minecraft:white_wool", 5);
        put("minecraft:pink_wool", "minecraft:white_wool", 6);
        put("minecraft:gray_wool", "minecraft:white_wool", 7);
        put("minecraft:light_gray_wool", "minecraft:white_wool", 8);
        put("minecraft:cyan_wool", "minecraft:white_wool", 9);
        put("minecraft:purple_wool", "minecraft:white_wool", 10);
        put("minecraft:blue_wool", "minecraft:white_wool", 11);
        put("minecraft:brown_wool", "minecraft:white_wool", 12);
        put("minecraft:green_wool", "minecraft:white_wool", 13);
        put("minecraft:red_wool", "minecraft:white_wool", 14);
        put("minecraft:black_wool", "minecraft:white_wool", 15);

        // Stained glass panes: all variants map to white_stained_glass_pane (legacy ID 160)
        put("minecraft:white_stained_glass_pane", 0);
        put("minecraft:orange_stained_glass_pane", "minecraft:white_stained_glass_pane", 1);
        put("minecraft:magenta_stained_glass_pane", "minecraft:white_stained_glass_pane", 2);
        put("minecraft:light_blue_stained_glass_pane", "minecraft:white_stained_glass_pane", 3);
        put("minecraft:yellow_stained_glass_pane", "minecraft:white_stained_glass_pane", 4);
        put("minecraft:lime_stained_glass_pane", "minecraft:white_stained_glass_pane", 5);
        put("minecraft:pink_stained_glass_pane", "minecraft:white_stained_glass_pane", 6);
        put("minecraft:gray_stained_glass_pane", "minecraft:white_stained_glass_pane", 7);
        put("minecraft:light_gray_stained_glass_pane", "minecraft:white_stained_glass_pane", 8);
        put("minecraft:cyan_stained_glass_pane", "minecraft:white_stained_glass_pane", 9);
        put("minecraft:purple_stained_glass_pane", "minecraft:white_stained_glass_pane", 10);
        put("minecraft:blue_stained_glass_pane", "minecraft:white_stained_glass_pane", 11);
        put("minecraft:brown_stained_glass_pane", "minecraft:white_stained_glass_pane", 12);
        put("minecraft:green_stained_glass_pane", "minecraft:white_stained_glass_pane", 13);
        put("minecraft:red_stained_glass_pane", "minecraft:white_stained_glass_pane", 14);
        put("minecraft:black_stained_glass_pane", "minecraft:white_stained_glass_pane", 15);

        // Terracotta: all variants map to white_terracotta (legacy ID 159)
        put("minecraft:white_terracotta", 0);
        put("minecraft:orange_terracotta", "minecraft:white_terracotta", 1);
        put("minecraft:magenta_terracotta", "minecraft:white_terracotta", 2);
        put("minecraft:light_blue_terracotta", "minecraft:white_terracotta", 3);
        put("minecraft:yellow_terracotta", "minecraft:white_terracotta", 4);
        put("minecraft:lime_terracotta", "minecraft:white_terracotta", 5);
        put("minecraft:pink_terracotta", "minecraft:white_terracotta", 6);
        put("minecraft:gray_terracotta", "minecraft:white_terracotta", 7);
        put("minecraft:light_gray_terracotta", "minecraft:white_terracotta", 8);
        put("minecraft:cyan_terracotta", "minecraft:white_terracotta", 9);
        put("minecraft:purple_terracotta", "minecraft:white_terracotta", 10);
        put("minecraft:blue_terracotta", "minecraft:white_terracotta", 11);
        put("minecraft:brown_terracotta", "minecraft:white_terracotta", 12);
        put("minecraft:green_terracotta", "minecraft:white_terracotta", 13);
        put("minecraft:red_terracotta", "minecraft:white_terracotta", 14);
        put("minecraft:black_terracotta", "minecraft:white_terracotta", 15);

        // Dyes: all variants map to ink_sac (legacy ID 351)
        put("minecraft:ink_sac", 0);
        put("minecraft:red_dye", "minecraft:ink_sac", 1);
        put("minecraft:green_dye", "minecraft:ink_sac", 2);
        put("minecraft:cocoa_beans", "minecraft:ink_sac", 3);
        put("minecraft:lapis_lazuli", "minecraft:ink_sac", 4);
        put("minecraft:purple_dye", "minecraft:ink_sac", 5);
        put("minecraft:cyan_dye", "minecraft:ink_sac", 6);
        put("minecraft:light_gray_dye", "minecraft:ink_sac", 7);
        put("minecraft:gray_dye", "minecraft:ink_sac", 8);
        put("minecraft:pink_dye", "minecraft:ink_sac", 9);
        put("minecraft:lime_dye", "minecraft:ink_sac", 10);
        put("minecraft:yellow_dye", "minecraft:ink_sac", 11);
        put("minecraft:light_blue_dye", "minecraft:ink_sac", 12);
        put("minecraft:magenta_dye", "minecraft:ink_sac", 13);
        put("minecraft:orange_dye", "minecraft:ink_sac", 14);
        put("minecraft:bone_meal", "minecraft:ink_sac", 15);

        // Beds: all variants map to red_bed (legacy ID 355)
        put("minecraft:white_bed", "minecraft:red_bed", 0);
        put("minecraft:orange_bed", "minecraft:red_bed", 1);
        put("minecraft:magenta_bed", "minecraft:red_bed", 2);
        put("minecraft:light_blue_bed", "minecraft:red_bed", 3);
        put("minecraft:yellow_bed", "minecraft:red_bed", 4);
        put("minecraft:lime_bed", "minecraft:red_bed", 5);
        put("minecraft:pink_bed", "minecraft:red_bed", 6);
        put("minecraft:gray_bed", "minecraft:red_bed", 7);
        put("minecraft:light_gray_bed", "minecraft:red_bed", 8);
        put("minecraft:cyan_bed", "minecraft:red_bed", 9);
        put("minecraft:purple_bed", "minecraft:red_bed", 10);
        put("minecraft:blue_bed", "minecraft:red_bed", 11);
        put("minecraft:brown_bed", "minecraft:red_bed", 12);
        put("minecraft:green_bed", "minecraft:red_bed", 13);
        put("minecraft:red_bed", 14);
        put("minecraft:black_bed", "minecraft:red_bed", 15);

        // Signs
        put("minecraft:oak_sign", 0);

        OLDER_EQUIVALENTS.put("minecraft:spectral_arrow", "minecraft:arrow");
        OLDER_EQUIVALENTS.put("minecraft:tipped_arrow", "minecraft:arrow");
    }

    private void put(String identifier, int dataValue) {
        put(identifier, identifier, dataValue);
    }

    private void put(String identifier, String baseType, int dataValue) {
        MAPPINGS.put(identifier, new LegacyEntry(baseType, dataValue));
    }

    public Optional<ItemStack.Builder> itemStackFor(String identifier, ClientVersion version) {
        LegacyEntry entry = entryFor(identifier, version);
        ItemType type = ItemTypes.getByName(entry.getTypeIdentifier());
        if (type == null || !LegacyItemIds.existsOn(type.getId(version), version)) {
            return Optional.empty();
        }
        return Optional.of(ItemStack.builder().type(type).legacyData(entry.getDataValue()));
    }

    public ItemStack.Builder resolve(String identifier, ClientVersion version, Consumer<String> warning) {
        Optional<ItemStack.Builder> exact = itemStackFor(identifier, version);
        if (exact.isPresent()) {
            return exact.get();
        }
        String older = OLDER_EQUIVALENTS.get(identifier);
        Optional<ItemStack.Builder> equivalent = older != null ? itemStackFor(older, version) : Optional.empty();
        if (equivalent.isPresent()) {
            return equivalent.get();
        }
        if (REPORTED_FALLBACKS.add(identifier + "@" + version.name())) {
            warning.accept(unavailableMessage(identifier, version));
        }
        return ItemStack.builder().type(ItemTypes.STONE);
    }

    private String unavailableMessage(String identifier, ClientVersion version) {
        return ItemTypes.getByName(identifier) == null
                ? "Unknown item type " + identifier + ", using STONE"
                : "Item type " + identifier + " is not available for client version " + version.getReleaseName() + ", using STONE";
    }

    private LegacyEntry entryFor(String identifier, ClientVersion version) {
        LegacyEntry legacy = version.isOlderThan(ClientVersion.V_1_13) ? MAPPINGS.get(identifier) : null;
        return legacy != null ? legacy : new LegacyEntry(identifier, 0);
    }
}
