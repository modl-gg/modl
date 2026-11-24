package gg.modl.minecraft.core.imports.advancedban;

import gg.modl.minecraft.core.imports.source.PunishmentKind;
import gg.modl.minecraft.core.imports.source.PunishmentScope;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

@Getter
@RequiredArgsConstructor
enum AdvancedBanType {
    BAN(PunishmentKind.BAN, PunishmentScope.ACCOUNT),
    TEMP_BAN(PunishmentKind.BAN, PunishmentScope.ACCOUNT),
    IP_BAN(PunishmentKind.BAN, PunishmentScope.IP_WIDE),
    TEMP_IP_BAN(PunishmentKind.BAN, PunishmentScope.IP_WIDE),
    MUTE(PunishmentKind.MUTE, PunishmentScope.ACCOUNT),
    TEMP_MUTE(PunishmentKind.MUTE, PunishmentScope.ACCOUNT),
    WARNING(PunishmentKind.WARNING, PunishmentScope.ACCOUNT),
    TEMP_WARNING(PunishmentKind.WARNING, PunishmentScope.ACCOUNT),
    KICK(PunishmentKind.KICK, PunishmentScope.ACCOUNT),
    NOTE(PunishmentKind.NOTE, PunishmentScope.ACCOUNT);

    private final PunishmentKind kind;
    private final PunishmentScope scope;

    boolean isAddressWide() {
        return scope == PunishmentScope.IP_WIDE;
    }

    static @Nullable AdvancedBanType of(@Nullable String name) {
        if (name == null) return null;
        for (AdvancedBanType type : values()) {
            if (type.name().equalsIgnoreCase(name.trim())) return type;
        }
        return null;
    }
}
