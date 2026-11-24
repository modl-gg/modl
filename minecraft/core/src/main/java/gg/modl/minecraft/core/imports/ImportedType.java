package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.core.imports.source.PunishmentKind;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
enum ImportedType {
    KICK(0),
    MUTE(1),
    BAN(2);

    private final int ordinal;

    static ImportedType of(PunishmentKind kind) {
        if (kind.isNote()) throw new IllegalArgumentException(kind + " is imported as a player note");
        return valueOf(kind.name());
    }
}
