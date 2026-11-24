package gg.modl.minecraft.core.imports.support;

import gg.modl.minecraft.core.imports.source.PunishmentKind;
import gg.modl.minecraft.core.imports.source.PunishmentScope;
import gg.modl.minecraft.core.imports.source.SourceInventory;

import java.util.EnumMap;
import java.util.Map;

public final class InventoryCounter {
    private final Map<PunishmentKind, Integer> byKind = new EnumMap<>(PunishmentKind.class);
    private int ipWide, serverScoped, withoutAccount;

    public void counted(PunishmentKind kind) {
        counted(kind, 1);
    }

    public void counted(PunishmentKind kind, long rows) {
        byKind.put(kind, (int) Math.min(Integer.MAX_VALUE, of(kind) + rows));
    }

    public void scoped(PunishmentScope scope) {
        if (scope == PunishmentScope.IP_WIDE) ipWide++;
        else if (scope == PunishmentScope.SERVER_SCOPED) serverScoped++;
    }

    public void withoutAccount() {
        withoutAccount(1);
    }

    public void withoutAccount(long rows) {
        withoutAccount = (int) Math.min(Integer.MAX_VALUE, withoutAccount + rows);
    }

    public SourceInventory toInventory(int players) {
        return SourceInventory.builder()
                .players(players)
                .bans(of(PunishmentKind.BAN))
                .mutes(of(PunishmentKind.MUTE))
                .warnings(of(PunishmentKind.WARNING))
                .kicks(of(PunishmentKind.KICK))
                .notes(of(PunishmentKind.NOTE))
                .ipWide(ipWide)
                .serverScoped(serverScoped)
                .withoutAccount(withoutAccount)
                .build();
    }

    private int of(PunishmentKind kind) {
        return byKind.getOrDefault(kind, 0);
    }
}
