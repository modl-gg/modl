package dev.simplix.cirrus.common.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyItemMappingTest {

    @BeforeEach
    void setUpPacketEventsApi() {
        PacketEvents.setAPI(new TestPacketEventsApi());
    }

    @AfterEach
    void clearPacketEventsApi() {
        PacketEvents.setAPI(null);
    }

    @Test
    void aFlattenedDyeHasNoItemIdOfItsOwnBefore113() {
        assertEquals(-1, ItemTypes.LIME_DYE.getId(ClientVersion.V_1_8));
    }

    @Test
    void aFlattenedDyeBecomesItsLegacyBaseItemWithItsDataValueBefore113() {
        ItemStack item = LegacyItemMapping.itemStackFor("minecraft:lime_dye", ClientVersion.V_1_8).orElseThrow(AssertionError::new).build();

        assertEquals(ItemTypes.INK_SAC, item.getType());
        assertEquals(10, item.getLegacyData());
    }

    @Test
    void aFlattenedItemKeepsItsOwnTypeFrom113() {
        ItemStack item = LegacyItemMapping.itemStackFor("minecraft:lime_dye", ClientVersion.V_1_21).orElseThrow(AssertionError::new).build();

        assertEquals(ItemTypes.LIME_DYE, item.getType());
    }

    @Test
    void anItemTheVersionDoesNotHaveResolvesToNothing() {
        assertFalse(LegacyItemMapping.itemStackFor("minecraft:spyglass", ClientVersion.V_1_8).isPresent());
    }

    @Test
    void anItemAddedAfter18ThatPacketEventsGivesALegacyIdResolvesToNothingOn18() {
        assertEquals(251, ItemTypes.WHITE_CONCRETE.getId(ClientVersion.V_1_8));

        for (String identifier : new String[]{"minecraft:white_concrete", "minecraft:white_glazed_terracotta",
                "minecraft:shulker_box", "minecraft:observer", "minecraft:magma_block", "minecraft:end_rod",
                "minecraft:shield", "minecraft:elytra", "minecraft:end_crystal", "minecraft:totem_of_undying",
                "minecraft:iron_nugget", "minecraft:knowledge_book", "minecraft:structure_block"}) {
            assertFalse(LegacyItemMapping.itemStackFor(identifier, ClientVersion.V_1_8).isPresent(), identifier);
        }
    }

    @Test
    void anItemAddedAfter18ResolvesFromTheVersionThatAddedIt() {
        assertFalse(LegacyItemMapping.itemStackFor("minecraft:white_concrete", ClientVersion.V_1_11_1).isPresent());
        assertEquals(ItemTypes.WHITE_CONCRETE, typeOn("minecraft:white_concrete", ClientVersion.V_1_12));
        assertFalse(LegacyItemMapping.itemStackFor("minecraft:shield", ClientVersion.V_1_8).isPresent());
        assertEquals(ItemTypes.SHIELD, typeOn("minecraft:shield", ClientVersion.V_1_9));
        assertFalse(LegacyItemMapping.itemStackFor("minecraft:iron_nugget", ClientVersion.V_1_11).isPresent());
        assertEquals(ItemTypes.IRON_NUGGET, typeOn("minecraft:iron_nugget", ClientVersion.V_1_11_1));
    }

    @Test
    void itemsThat18HasKeepResolvingOn18() {
        for (String identifier : new String[]{"minecraft:stone", "minecraft:barrier", "minecraft:slime_block",
                "minecraft:dark_oak_door", "minecraft:prismarine_shard", "minecraft:armor_stand", "minecraft:music_disc_11",
                "minecraft:compass", "minecraft:paper", "minecraft:red_bed", "minecraft:player_head"}) {
            assertTrue(LegacyItemMapping.itemStackFor(identifier, ClientVersion.V_1_8).isPresent(), identifier);
        }
    }

    @Test
    void anUnknownItemResolvesToNothing() {
        assertFalse(LegacyItemMapping.itemStackFor("minecraft:no_such_item", ClientVersion.V_1_8).isPresent());
    }

    @Test
    void anItemWithAnOlderEquivalentUsesItSilentlyBefore19() {
        List<String> warnings = new ArrayList<>();

        assertEquals(ItemTypes.ARROW, LegacyItemMapping.resolve("minecraft:spectral_arrow", ClientVersion.V_1_8, warnings::add)
                .build().getType());
        assertEquals(ItemTypes.SPECTRAL_ARROW, LegacyItemMapping.resolve("minecraft:spectral_arrow", ClientVersion.V_1_9,
                warnings::add).build().getType());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void anItemTheVersionLacksFallsBackToStoneWithOneWarning() {
        List<String> warnings = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            assertEquals(ItemTypes.STONE, LegacyItemMapping.resolve("minecraft:white_concrete", ClientVersion.V_1_9_3,
                    warnings::add).build().getType());
        }
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("minecraft:white_concrete"), warnings.get(0));
        assertTrue(warnings.get(0).contains("is not available"), warnings.get(0));
    }

    @Test
    void anUnknownItemFallsBackToStoneAndSaysItIsUnknown() {
        List<String> warnings = new ArrayList<>();

        assertEquals(ItemTypes.STONE, LegacyItemMapping.resolve("minecraft:typo_item", ClientVersion.V_1_21, warnings::add)
                .build().getType());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).startsWith("Unknown item type minecraft:typo_item"), warnings.get(0));
    }

    @Test
    void everyItemModlShipsResolvesOn18WithoutFallingBack() {
        List<String> warnings = new ArrayList<>();
        for (String identifier : new String[]{"anvil", "arrow", "barrier", "blaze_rod", "book", "bow", "command_block",
                "compass", "diamond_sword", "emerald", "ender_eye", "experience_bottle", "glass_bottle", "golden_apple",
                "gray_dye", "gray_stained_glass_pane", "iron_chestplate", "iron_sword", "leather_boots", "lime_dye",
                "lime_wool", "oak_sign", "paper", "player_head", "red_bed", "red_wool", "redstone", "redstone_torch",
                "spectral_arrow", "torch", "vine", "writable_book", "yellow_wool", "armor_stand", "chest", "creeper_head",
                "feather", "gold_ingot", "ice", "ink_sac", "lava_bucket", "lead", "name_tag", "nether_star", "pufferfish",
                "repeater", "spider_eye", "tnt"}) {
            assertFalse(ItemTypes.STONE.equals(LegacyItemMapping.resolve("minecraft:" + identifier, ClientVersion.V_1_8,
                    warnings::add).build().getType()), identifier);
        }
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    private static ItemType typeOn(String identifier, ClientVersion version) {
        return LegacyItemMapping.itemStackFor(identifier, version).orElseThrow(AssertionError::new).build().getType();
    }
}
