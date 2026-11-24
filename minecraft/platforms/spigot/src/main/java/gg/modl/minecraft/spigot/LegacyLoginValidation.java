package gg.modl.minecraft.spigot;

import lombok.RequiredArgsConstructor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

@RequiredArgsConstructor
final class LegacyLoginValidation implements Listener {
    private final LoginAdmission admission;

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        LoginDenial denial = admission.admit(event.getPlayer().getUniqueId(), event.getPlayer().getName(),
                event.getResult() == PlayerLoginEvent.Result.ALLOWED);
        if (denial == null) return;
        PlayerLoginEvent.Result result = denial.isRefusedByCheck()
                ? PlayerLoginEvent.Result.KICK_BANNED
                : PlayerLoginEvent.Result.KICK_OTHER;
        event.disallow(result, Colors.get(denial.getMessage()));
    }
}
