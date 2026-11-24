package gg.modl.minecraft.bungee;

import gg.modl.minecraft.core.boot.LoginGate;
import lombok.RequiredArgsConstructor;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

@RequiredArgsConstructor
public final class BungeeLoginGateListener implements Listener {
    private final LoginGate gate;

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(PreLoginEvent event) {
        if (gate.isOpen()) return;
        event.setCancelReason(Colors.get(gate.deniedMessage()));
        event.setCancelled(true);
    }
}
