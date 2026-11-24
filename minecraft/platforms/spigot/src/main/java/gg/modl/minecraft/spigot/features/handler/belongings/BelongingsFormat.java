package gg.modl.minecraft.spigot.features.handler.belongings;

import gg.modl.minecraft.features.staffmode.custody.BelongingsException;
import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BelongingsFormat {
    private static final Pattern MINECRAFT_VERSION = Pattern.compile("^\\d+(?:\\.\\d+)*");
    private static final int MAX_SLOTS = 256;

    private final ItemCodecs codecs;
    private final String minecraftVersion;

    BelongingsFormat(ItemCodecs codecs, String bukkitVersion) {
        this.codecs = codecs;
        this.minecraftVersion = minecraftVersionOf(bukkitVersion);
    }

    static String minecraftVersionOf(String bukkitVersion) {
        Matcher matcher = MINECRAFT_VERSION.matcher(bukkitVersion);
        return matcher.find() ? matcher.group() : bukkitVersion;
    }

    CapturedBelongings capture(@Nullable ItemStack[] contents, @Nullable ItemStack[] armor, GameMode mode, Vitals vitals)
            throws BelongingsException {
        StringBuilder text = new StringBuilder(minecraftVersion).append('\n')
                .append(mode.name()).append('\n')
                .append(vitals.validated().write()).append('\n')
                .append(contents.length).append('\n')
                .append(armor.length);
        @Nullable ItemStack[] ownContents = copyAndAppend(contents, text);
        @Nullable ItemStack[] ownArmor = copyAndAppend(armor, text);
        return new CapturedBelongings(ownContents, ownArmor, mode, vitals, text.toString());
    }

    private @Nullable ItemStack[] copyAndAppend(@Nullable ItemStack[] items, StringBuilder text)
            throws BelongingsException {
        @Nullable ItemStack[] copies = new ItemStack[items.length];
        for (int slot = 0; slot < items.length; slot++) {
            ItemStack item = items[slot];
            copies[slot] = item == null ? null : item.clone();
            text.append('\n').append(codecs.encode(item));
        }
        return copies;
    }

    boolean areIdentical(@Nullable ItemStack[] expected, @Nullable ItemStack[] actual) {
        return codecs.areIdentical(expected, actual);
    }

    CapturedBelongings read(String encoded) throws BelongingsException {
        String[] lines = encoded.split("\n", -1);
        if (lines.length < 5) throw new BelongingsException("The saved belongings are cut off");
        boolean sameVersion = minecraftVersion.equals(lines[0]);
        GameMode mode;
        int contentSlots;
        int armorSlots;
        try {
            mode = GameMode.valueOf(lines[1]);
            contentSlots = Integer.parseInt(lines[3]);
            armorSlots = Integer.parseInt(lines[4]);
        } catch (IllegalArgumentException failure) {
            throw ReflectiveCalls.failed("read the saved belongings", failure);
        }
        if (contentSlots < 0 || contentSlots > MAX_SLOTS || armorSlots < 0 || armorSlots > MAX_SLOTS
                || lines.length != 5 + contentSlots + armorSlots) {
            throw new BelongingsException("The saved belongings have the wrong number of slots");
        }
        Vitals vitals = Vitals.read(lines[2]);
        @Nullable ItemStack[] contents = readItems(lines, 5, contentSlots, sameVersion);
        @Nullable ItemStack[] armor = readItems(lines, 5 + contentSlots, armorSlots, sameVersion);
        return new CapturedBelongings(contents, armor, mode, vitals, encoded);
    }

    private @Nullable ItemStack[] readItems(String[] lines, int firstLine, int count, boolean sameVersion)
            throws BelongingsException {
        @Nullable ItemStack[] items = new ItemStack[count];
        for (int slot = 0; slot < count; slot++) items[slot] = codecs.decode(lines[firstLine + slot], sameVersion);
        return items;
    }
}
