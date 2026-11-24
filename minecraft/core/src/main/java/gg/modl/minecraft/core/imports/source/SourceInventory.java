package gg.modl.minecraft.core.imports.source;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SourceInventory {
    int players, bans, mutes, warnings, kicks, notes, ipWide, serverScoped, withoutAccount;

    public boolean isEmpty() {
        return players == 0;
    }
}
