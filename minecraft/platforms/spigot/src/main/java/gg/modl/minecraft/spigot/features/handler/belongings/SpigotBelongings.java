package gg.modl.minecraft.spigot.features.handler.belongings;

import gg.modl.minecraft.features.staffmode.custody.Belongings;
import gg.modl.minecraft.features.staffmode.custody.BelongingsException;
import gg.modl.minecraft.features.staffmode.custody.BelongingsPort;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

public final class SpigotBelongings implements BelongingsPort {
    private static final String NO_MARK = "none";
    private static final int ARMOR_SLOTS = 4;

    private final Optional<CustodyMark> mark;

    private volatile @Nullable BelongingsFormat format;

    public SpigotBelongings(Plugin plugin) {
        Optional<CustodyMark> persistentData = PersistentDataMark.detect(plugin);
        this.mark = persistentData.isPresent() ? persistentData : AttributeMark.detect();
    }

    @Override
    public String markKind() {
        return mark.map(CustodyMark::kind).orElse(NO_MARK);
    }

    @Override
    public Optional<UUID> custodyMark(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) throw new IllegalStateException(uuid + " is not online");
        return mark.flatMap(present -> present.read(player));
    }

    @Override
    public void clearMark(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) mark.ifPresent(present -> present.clear(player));
    }

    @Override
    public boolean isAlive(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null && !player.isDead() && player.getHealth() > 0;
    }

    @Override
    public boolean hasSavedPlayerData(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player == null || player.hasPlayedBefore();
    }

    @Override
    public Belongings capture(UUID uuid) throws BelongingsException {
        Player player = online(uuid);
        if (!mark.isPresent()) throw new BelongingsException("This server offers no way to mark player data");
        BelongingsFormat itemFormat = format();
        player.closeInventory();
        if (!isEmpty(player.getItemOnCursor())) throw new BelongingsException("An item is still on the cursor");
        PlayerInventory inventory = player.getInventory();
        return itemFormat.capture(inventory.getContents(), inventory.getArmorContents(), player.getGameMode(),
                Vitals.of(player));
    }

    @Override
    public Belongings decode(String encoded) throws BelongingsException {
        return format().read(encoded);
    }

    @Override
    public boolean isUnchanged(UUID uuid, Belongings captured) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return false;
        BelongingsFormat itemFormat = format;
        if (itemFormat == null) throw new IllegalStateException("belongings were compared before any were captured");
        player.closeInventory();
        CapturedBelongings expected = (CapturedBelongings) captured;
        PlayerInventory inventory = player.getInventory();
        return isEmpty(player.getItemOnCursor())
                && player.getGameMode() == expected.getMode()
                && expected.getVitals().hasSameExperience(player)
                && itemFormat.areIdentical(expected.getContents(), inventory.getContents())
                && itemFormat.areIdentical(expected.getArmor(), inventory.getArmorContents());
    }

    @Override
    public void takeAway(UUID uuid, UUID custody) throws BelongingsException {
        Player player = online(uuid);
        CustodyMark custodyMark = mark.orElseThrow(() -> new BelongingsException("This server offers no way to mark player data"));
        custodyMark.write(player, custody);
        if (!Optional.of(custody).equals(custodyMark.read(player))) {
            custodyMark.clear(player);
            throw new BelongingsException("The custody mark did not stick to the player data");
        }
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[ARMOR_SLOTS]);
    }

    @Override
    public void giveBack(UUID uuid, Belongings belongings) throws BelongingsException {
        Player player = online(uuid);
        CapturedBelongings saved = (CapturedBelongings) belongings;
        PlayerInventory inventory = player.getInventory();
        @Nullable ItemStack[] contents = fittedTo(saved.getContents(), inventory.getContents().length);
        @Nullable ItemStack[] armor = fittedTo(saved.getArmor(), inventory.getArmorContents().length);

        player.setGameMode(saved.getMode());
        if (player.getGameMode() != saved.getMode()) {
            throw new BelongingsException("The change back to " + saved.getMode() + " was refused");
        }
        player.setItemOnCursor(null);
        player.closeInventory();
        inventory.setContents(contents);
        inventory.setArmorContents(armor);
        saved.getVitals().applyTo(player);
        mark.ifPresent(present -> present.clear(player));
    }

    @Override
    public void leaveCreative(UUID uuid, @Nullable String recordedGameMode) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || player.getGameMode() != GameMode.CREATIVE) return;
        player.setGameMode(gameModeNamed(recordedGameMode).orElseGet(Bukkit::getDefaultGameMode));
    }

    private static Optional<GameMode> gameModeNamed(@Nullable String name) {
        for (GameMode mode : GameMode.values()) {
            if (mode.name().equals(name)) return Optional.of(mode);
        }
        return Optional.empty();
    }

    static @Nullable ItemStack[] fittedTo(@Nullable ItemStack[] saved, int size) throws BelongingsException {
        @Nullable ItemStack[] fitted = new ItemStack[size];
        for (int slot = 0; slot < saved.length; slot++) {
            ItemStack item = saved[slot];
            if (slot < size) {
                fitted[slot] = item == null ? null : item.clone();
            } else if (!isEmpty(item)) {
                throw new BelongingsException("This server has fewer inventory slots than the saved belongings");
            }
        }
        return fitted;
    }

    private BelongingsFormat format() throws BelongingsException {
        BelongingsFormat current = format;
        if (current == null) {
            current = new BelongingsFormat(ItemCodecs.forThisServer(), Bukkit.getBukkitVersion());
            format = current;
        }
        return current;
    }

    private static Player online(UUID uuid) throws BelongingsException {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) throw new BelongingsException(uuid + " is not online");
        return player;
    }

    private static boolean isEmpty(@Nullable ItemStack item) {
        return item == null || item.getType() == Material.AIR;
    }
}
