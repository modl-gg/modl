package gg.modl.minecraft.spigot.boot;

import gg.modl.minecraft.core.boot.LoginGate;
import gg.modl.minecraft.spigot.Colors;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

@RequiredArgsConstructor
public final class SpigotLoginGateListener implements Listener {
    private final LoginGate gate;

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (gate.isOpen()) return;
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Colors.get(gate.deniedMessage()));
    }
}
