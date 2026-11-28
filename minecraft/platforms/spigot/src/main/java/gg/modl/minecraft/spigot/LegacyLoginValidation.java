package gg.modl.minecraft.spigot;

import lombok.RequiredArgsConstructor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

@RequiredArgsConstructor
final class LegacyLoginValidation implements Listener {
    private final LoginAdmission admission;

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(AsyncPlayerPreLoginEvent event) {
        LoginDenial denial = admission.admit(event.getUniqueId(), event.getName(),
                event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED);
        if (denial == null) return;

        AsyncPlayerPreLoginEvent.Result result = denial.isRefusedByCheck()
                ? AsyncPlayerPreLoginEvent.Result.KICK_BANNED
                : AsyncPlayerPreLoginEvent.Result.KICK_OTHER;
        event.disallow(result, Colors.get(denial.getMessage()));
    }
}
