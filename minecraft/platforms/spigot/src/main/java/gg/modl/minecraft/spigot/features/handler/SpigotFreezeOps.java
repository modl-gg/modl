package gg.modl.minecraft.spigot.features.handler;

import gg.modl.minecraft.features.FeatureScheduler;
import gg.modl.minecraft.features.freeze.FreezeOps;
import gg.modl.minecraft.spigot.features.folia.AsyncTeleporter;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
class SpigotFreezeOps implements FreezeOps {
    private final FeatureScheduler scheduler;
    private final AsyncTeleporter teleporter;
    private final Map<UUID, FreezeAnchor> anchors = new ConcurrentHashMap<>();

    @Override
    public void sendMessage(UUID uuid, String message) {
        scheduler.runForPlayer(uuid, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendMessage(message);
            }
        });
    }

    @Override
    public void onFrozen(UUID target) {
        scheduler.runForPlayer(target, () -> {
            Player player = Bukkit.getPlayer(target);
            if (player == null) return;
            player.leaveVehicle();
            player.setVelocity(new Vector(0, 0, 0));
            captureAnchor(target, player.getLocation());
        });
    }

    @Override
    public void onUnfrozen(UUID target) {
        anchors.remove(target);
    }

    @Nullable FreezeAnchor anchor(UUID uuid) {
        return anchors.get(uuid);
    }

    void captureAnchor(UUID uuid, Location location) {
        if (location.getWorld() == null) return;
        anchors.put(uuid, FreezeAnchor.of(location));
    }

    void returnToAnchor(UUID uuid) {
        FreezeAnchor anchor = anchors.get(uuid);
        if (anchor == null) return;

        World world = Bukkit.getWorld(anchor.getWorldId());
        if (world == null) return;

        scheduler.runForPlayer(uuid, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) return;
            player.setVelocity(new Vector(0, 0, 0));
            teleporter.teleport(player, anchor.toLocation(world, player.getLocation().getYaw(), player.getLocation().getPitch()));
        });
    }
}
