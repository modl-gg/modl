package gg.modl.backend.player.data.punishment;

public record PunishmentOptionChange(PunishmentToggleOption option, boolean enabled) {
    public boolean endsAltBlocking() {
        return option == PunishmentToggleOption.ALT_BLOCKING && !enabled;
    }
}
