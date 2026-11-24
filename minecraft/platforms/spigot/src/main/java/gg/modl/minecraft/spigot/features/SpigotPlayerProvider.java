package gg.modl.minecraft.spigot.features;

import gg.modl.minecraft.features.FeaturePlayer;
import gg.modl.minecraft.features.FeaturePlayerProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;
import java.util.stream.Collectors;

public class SpigotPlayerProvider implements FeaturePlayerProvider {

    @Override
    public Collection<FeaturePlayer> getOnlinePlayers() {
        return Bukkit.getOnlinePlayers().stream()
                .map(SpigotFeaturePlayer::new)
                .collect(Collectors.toList());
    }

    @Override
    public boolean isOnline(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null && player.isOnline();
    }

    @Override
    public void dispatchConsoleCommand(String command) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }
}
