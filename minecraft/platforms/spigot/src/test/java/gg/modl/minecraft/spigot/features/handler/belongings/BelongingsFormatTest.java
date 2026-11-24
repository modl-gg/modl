package gg.modl.minecraft.spigot.features.handler.belongings;

import gg.modl.minecraft.features.staffmode.custody.BelongingsException;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BelongingsFormatTest {
    private static final ItemFidelity SAME_TYPE_AMOUNT_AND_DAMAGE = (expected, actual) ->
            expected.getType() == actual.getType() && expected.getAmount() == actual.getAmount()
                    && expected.getDurability() == actual.getDurability();

    private final Vitals vitals = new Vitals(1395, 30, 0.42f, 17.5, 14, 3.25f, 1.5f);

    @Test
    void everySlotArmorOffHandAndVitalsRoundTrip() throws BelongingsException {
        BelongingsFormat format = format("1.21.11-R0.1-SNAPSHOT", new SimpleCodec("plain", false, true));
        ItemStack[] contents = new ItemStack[41];
        for (int slot = 0; slot < contents.length; slot++) contents[slot] = new ItemStack(Material.STONE, slot + 1, (short) slot);
        contents[7] = null;
        ItemStack[] armor = Arrays.copyOfRange(contents, 36, 40);

        String saved = format.capture(contents, armor, GameMode.ADVENTURE, vitals).encoded();
        CapturedBelongings read = format.read(saved);

        assertEquals(41, read.getContents().length);
        assertNull(read.getContents()[7]);
        for (int slot = 0; slot < contents.length; slot++) {
            if (slot != 7) assertTrue(SAME_TYPE_AMOUNT_AND_DAMAGE.areIdentical(contents[slot], read.getContents()[slot]));
        }
        assertEquals(40, read.getArmor()[3].getAmount());
        assertEquals(GameMode.ADVENTURE, read.getMode());
        assertEquals(vitals, read.getVitals());
        assertEquals(saved, read.encoded());
    }

    @Test
    void theSnapshotDoesNotFollowTheLiveInventory() throws BelongingsException {
        ItemStack[] contents = {new ItemStack(Material.DIAMOND, 5)};

        CapturedBelongings captured = format("1.8.8", new SimpleCodec("plain", false, true))
                .capture(contents, new ItemStack[4], GameMode.SURVIVAL, vitals);
        contents[0].setAmount(1);

        assertEquals(5, captured.getContents()[0].getAmount());
    }

    @Test
    void anotherMinecraftVersionCannotReadAVersionBoundItem() throws BelongingsException {
        String saved = oneDiamond(format("1.20.4-R0.1-SNAPSHOT", new SimpleCodec("plain", false, true)));

        BelongingsFormat upgraded = format("1.21.11-R0.1-SNAPSHOT", new SimpleCodec("plain", false, true));

        assertThrows(BelongingsException.class, () -> upgraded.read(saved));
    }

    @Test
    void aSelfUpgradingItemSurvivesAnUpgrade() throws BelongingsException {
        String saved = oneDiamond(format("1.21.11-R0.1-SNAPSHOT", new SimpleCodec("paper", true, true)));

        CapturedBelongings read = format("26.1.2.build.74-stable", new SimpleCodec("paper", true, true)).read(saved);

        assertEquals(Material.DIAMOND, read.getContents()[0].getType());
    }

    @Test
    void aBuildUpdateOfTheSameMinecraftVersionStillReads() throws BelongingsException {
        String saved = oneDiamond(format("26.1.2.build.74-stable", new SimpleCodec("plain", false, true)));

        assertEquals(3, format("26.1.2.build.90-stable", new SimpleCodec("plain", false, true))
                .read(saved).getContents()[0].getAmount());
    }

    @Test
    void aLosslessCodecIsPreferredOverALossyOne() throws BelongingsException {
        String saved = oneDiamond(format("1.8.8", new SimpleCodec("lossy", false, false), new SimpleCodec("exact", false, true)));

        assertTrue(saved.contains("exact:"));
        assertTrue(!saved.contains("lossy:"));
    }

    @Test
    void anItemNoCodecKeepsExactlyRefusesTheSnapshot() {
        BelongingsFormat format = format("1.8.8", new SimpleCodec("lossy", false, false));

        assertThrows(BelongingsException.class, () -> oneDiamond(format));
    }

    @Test
    void aRecordWithMissingSlotsIsNotPartlyRestored() throws BelongingsException {
        BelongingsFormat format = format("1.8.8", new SimpleCodec("plain", false, true));
        String saved = oneDiamond(format);

        assertThrows(BelongingsException.class, () -> format.read(saved.substring(0, saved.lastIndexOf('\n'))));
    }

    @Test
    void aStateTheServerWouldRejectIsNotSaved() {
        BelongingsFormat format = format("1.8.8", new SimpleCodec("plain", false, true));
        Vitals dead = new Vitals(0, 0, 0f, 0.0, 20, 5f, 0f);

        assertThrows(BelongingsException.class,
                () -> format.capture(new ItemStack[36], new ItemStack[4], GameMode.SURVIVAL, dead));
    }

    @Test
    void savedSlotsFitIntoALargerInventoryButNeverLoseItemsInASmallerOne() throws BelongingsException {
        ItemStack[] saved = new ItemStack[41];
        saved[40] = new ItemStack(Material.DIAMOND);

        assertEquals(Material.DIAMOND, SpigotBelongings.fittedTo(saved, 43)[40].getType());
        assertThrows(BelongingsException.class, () -> SpigotBelongings.fittedTo(saved, 36));
        assertEquals(36, SpigotBelongings.fittedTo(new ItemStack[41], 36).length);
    }

    private String oneDiamond(BelongingsFormat format) throws BelongingsException {
        return format.capture(new ItemStack[]{new ItemStack(Material.DIAMOND, 3)}, new ItemStack[4],
                GameMode.SURVIVAL, vitals).encoded();
    }

    private static BelongingsFormat format(String version, ItemCodec... codecs) {
        List<ItemCodec> chain = Collections.unmodifiableList(Arrays.asList(codecs));
        return new BelongingsFormat(new ItemCodecs(chain, SAME_TYPE_AMOUNT_AND_DAMAGE), version);
    }

    private static final class SimpleCodec implements ItemCodec {
        private final String id;
        private final boolean acrossVersions;
        private final boolean lossless;

        private SimpleCodec(String id, boolean acrossVersions, boolean lossless) {
            this.id = id;
            this.acrossVersions = acrossVersions;
            this.lossless = lossless;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean readsOtherVersions() {
            return acrossVersions;
        }

        @Override
        public byte[] encode(ItemStack item) {
            String amount = String.valueOf(lossless ? item.getAmount() : item.getAmount() + 1);
            return (item.getType().name() + " " + amount + " " + item.getDurability()).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public ItemStack decode(byte[] bytes) {
            String[] fields = new String(bytes, StandardCharsets.UTF_8).split(" ");
            return new ItemStack(Material.valueOf(fields[0]), Integer.parseInt(fields[1]), Short.parseShort(fields[2]));
        }
    }
}
