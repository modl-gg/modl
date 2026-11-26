package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDeathCombatEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetExperience;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateHealth;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class TargetPackets {
    private static final int PLAYER_WINDOW = 0;
    private static final int DIRECT_INVENTORY_WINDOW = -2;
    private static final int HOTBAR_START = 36;

    private final TranslationContext context;

    TargetPackets(TranslationContext context) {
        this.context = context;
    }

    void register(PacketHandlers handlers) {
        handlers.onSend(PacketType.Play.Server.UPDATE_HEALTH, this::health);
        handlers.onSend(PacketType.Play.Server.SET_EXPERIENCE, this::experience);
        handlers.onSend(PacketType.Play.Server.HELD_ITEM_CHANGE, this::heldSlot);
        handlers.onSend(PacketType.Play.Server.UPDATE_ATTRIBUTES, this::attributes);
        handlers.onSend(PacketType.Play.Server.WINDOW_ITEMS, this::windowItems);
        handlers.onSend(PacketType.Play.Server.SET_SLOT, this::setSlot);
        handlers.onSend(PacketType.Play.Server.SET_PLAYER_INVENTORY, this::setPlayerInventory);
        handlers.onSend(PacketType.Play.Server.DEATH_COMBAT_EVENT, this::death);
        handlers.onReceive(PacketType.Play.Client.PLAYER_POSITION, this::move);
        handlers.onReceive(PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION, this::move);
        handlers.onReceive(PacketType.Play.Client.PLAYER_ROTATION, this::move);
        handlers.onReceive(PacketType.Play.Client.PLAYER_FLYING, this::move);
        handlers.onReceive(PacketType.Play.Client.HELD_ITEM_CHANGE, this::selectSlot);
        handlers.onReceive(PacketType.Play.Client.ANIMATION, this::swing);
    }

    private void health(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerUpdateHealth packet = new WrapperPlayServerUpdateHealth(event);
        recording.append(RecordEncoder.targetHealth(time, packet.getHealth(), packet.getFood(), packet.getFoodSaturation()), time);
    }

    private void experience(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerSetExperience packet = new WrapperPlayServerSetExperience(event);
        recording.append(RecordEncoder.targetExperience(time, packet.getExperienceBar(), packet.getLevel(), packet.getTotalExperience()), time);
    }

    private void heldSlot(PacketSendEvent event, Recording recording, long time) {
        recording.append(RecordEncoder.targetSlot(time, new WrapperPlayServerHeldItemChange(event).getSlot()), time);
    }

    private void attributes(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerUpdateAttributes packet = new WrapperPlayServerUpdateAttributes(event);
        if (packet.getEntityId() != recording.targetEntityId()) return;
        Float maxHealth = null;
        Integer armor = null;
        for (WrapperPlayServerUpdateAttributes.Property property : packet.getProperties()) {
            String key = attributeKey(property);
            if ("maxhealth".equals(key)) maxHealth = (float) property.calcValue();
            else if ("armor".equals(key)) armor = (int) Math.floor(property.calcValue());
        }
        if (maxHealth != null || armor != null) recording.append(RecordEncoder.targetAttributes(time, maxHealth, armor), time);
    }

    static String attributeKey(WrapperPlayServerUpdateAttributes.Property property) {
        String key;
        try {
            key = property.getAttribute().getName().getKey();
        } catch (RuntimeException e) {
            return "";
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        int colon = normalized.indexOf(':');
        if (colon >= 0) normalized = normalized.substring(colon + 1);
        if (normalized.startsWith("generic.")) normalized = normalized.substring("generic.".length());
        return normalized.replace("_", "");
    }

    private void windowItems(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(event);
        if (packet.getWindowId() != PLAYER_WINDOW) return;
        List<ItemStack> items = packet.getItems();
        List<SlotItem> slots = new ArrayList<>(Math.min(items.size(), ModelTarget.INVENTORY_SLOTS));
        for (int i = 0; i < items.size() && i < ModelTarget.INVENTORY_SLOTS; i++) {
            ModelItem item = context.getItems().convert(items.get(i));
            if (item != null) slots.add(new SlotItem(i, item));
        }
        recording.append(RecordEncoder.targetInventory(time, true, slots), time);
    }

    private void setSlot(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
        int window = packet.getWindowId();
        int slot = packet.getSlot();
        if (window == PLAYER_WINDOW) {
            slot(recording, time, slot, packet.getItem());
        } else if (window == DIRECT_INVENTORY_WINDOW) {
            slot(recording, time, inventoryToMenuSlot(slot), packet.getItem());
        }
    }

    private void setPlayerInventory(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerSetPlayerInventory packet = new WrapperPlayServerSetPlayerInventory(event);
        slot(recording, time, inventoryToMenuSlot(packet.getSlot()), packet.getStack());
    }

    private void slot(Recording recording, long time, int slot, @Nullable ItemStack stack) {
        if (slot < 0 || slot >= ModelTarget.INVENTORY_SLOTS) return;
        recording.append(RecordEncoder.targetInventory(time, false,
                Collections.singletonList(new SlotItem(slot, context.getItems().convert(stack)))), time);
    }

    static int inventoryToMenuSlot(int inventorySlot) {
        if (inventorySlot >= 0 && inventorySlot <= 8) return HOTBAR_START + inventorySlot;
        if (inventorySlot >= 9 && inventorySlot <= 35) return inventorySlot;
        switch (inventorySlot) {
            case 36: return 8;
            case 37: return 7;
            case 38: return 6;
            case 39: return 5;
            case 40: return 45;
            default: return -1;
        }
    }

    private void death(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerDeathCombatEvent packet = new WrapperPlayServerDeathCombatEvent(event);
        recording.append(RecordEncoder.entityDeath(time, packet.getPlayerId()), time);
    }

    private void move(PacketReceiveEvent event, Recording recording, long time) {
        WrapperPlayClientPlayerFlying packet = new WrapperPlayClientPlayerFlying(event);
        Location location = packet.getLocation();
        int mask = 0;
        if (packet.hasPositionChanged()) {
            mask |= Records.MOVE_POSITION;
            recording.targetChunk(((int) Math.floor(location.getX())) >> 4, ((int) Math.floor(location.getZ())) >> 4);
        }
        if (packet.hasRotationChanged()) mask |= Records.MOVE_ROTATION;
        recording.append(RecordEncoder.targetMove(time, mask, Fixed.of(location.getX()), Fixed.of(location.getY()), Fixed.of(location.getZ()),
                location.getYaw(), location.getPitch(), packet.isOnGround()), time);
    }

    private void selectSlot(PacketReceiveEvent event, Recording recording, long time) {
        recording.append(RecordEncoder.targetSlot(time, new WrapperPlayClientHeldItemChange(event).getSlot()), time);
    }

    private void swing(PacketReceiveEvent event, Recording recording, long time) {
        WrapperPlayClientAnimation packet = new WrapperPlayClientAnimation(event);
        int animation = packet.getHand() == InteractionHand.OFF_HAND ? EntityPackets.ANIMATION_SWING_OFF_HAND : EntityPackets.ANIMATION_SWING_MAIN_HAND;
        recording.append(RecordEncoder.entityAnimation(time, recording.targetEntityId(), animation), time);
    }
}
