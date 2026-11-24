package gg.modl.minecraft.core.imports.litebans;

import gg.modl.minecraft.core.imports.source.PunishmentKind;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Locale;

@Getter
@RequiredArgsConstructor
enum LiteBansTable {
    BANS("{bans}", PunishmentKind.BAN, true),
    MUTES("{mutes}", PunishmentKind.MUTE, true),
    WARNINGS("{warnings}", PunishmentKind.WARNING, true),
    KICKS("{kicks}", PunishmentKind.KICK, false);

    private final String token;
    private final PunishmentKind kind;
    private final boolean removable;

    String importedId(int sourceId) {
        return "litebans-" + kind.name().toLowerCase(Locale.ROOT) + "-" + sourceId;
    }
}
