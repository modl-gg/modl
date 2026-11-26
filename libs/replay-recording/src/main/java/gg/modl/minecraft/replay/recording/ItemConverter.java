package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemDyeColor;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.enchantment.Enchantment;
import com.github.retrooper.packetevents.protocol.item.enchantment.type.EnchantmentType;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTNumber;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

final class ItemConverter {
    private static final int MAX_ENCHANTMENTS = 16;

    private final NameTable names;
    private final boolean components;

    ItemConverter(NameTable names, ServerVersion serverVersion) {
        this.names = names;
        this.components = serverVersion.isNewerThanOrEquals(ServerVersion.V_1_20_5);
    }

    @Nullable ModelItem convert(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        ItemType type = stack.getType();
        int name = names.id(type, () -> type.getName().toString());
        return new ModelItem(name, stack.getAmount(), enchanted(stack), enchantments(stack), dyeColor(stack),
                Math.max(0, safeDamage(stack)), Math.max(0, safeMaxDamage(stack)));
    }

    private boolean enchanted(ItemStack stack) {
        try {
            if (components) {
                Optional<Boolean> glint = stack.getComponent(ComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);
                if (glint.isPresent()) return glint.get();
            }
            return stack.isEnchanted();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private List<ModelItem.Enchant> enchantments(ItemStack stack) {
        try {
            List<Enchantment> enchantments = stack.getEnchantments();
            if (enchantments.isEmpty()) return Collections.emptyList();
            List<ModelItem.Enchant> converted = new ArrayList<>(Math.min(enchantments.size(), MAX_ENCHANTMENTS));
            for (Enchantment enchantment : enchantments) {
                if (converted.size() >= MAX_ENCHANTMENTS) break;
                EnchantmentType type = enchantment.getType();
                converted.add(new ModelItem.Enchant(names.id(type, () -> type.getName().toString()), enchantment.getLevel()));
            }
            return Collections.unmodifiableList(converted);
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    private @Nullable Integer dyeColor(ItemStack stack) {
        try {
            if (components) {
                Optional<ItemDyeColor> dye = stack.getComponent(ComponentTypes.DYED_COLOR);
                return dye.isPresent() ? dye.get().getRgb() : null;
            }
            NBTCompound tag = stack.getNBT();
            if (tag == null) return null;
            NBTCompound display = tag.getCompoundTagOrNull("display");
            if (display == null) return null;
            NBTNumber color = display.getNumberTagOrNull("color");
            return color == null ? null : color.getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int safeDamage(ItemStack stack) {
        try {
            return stack.isDamageableItem() ? stack.getDamageValue() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int safeMaxDamage(ItemStack stack) {
        try {
            return stack.isDamageableItem() ? stack.getMaxDamage() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
