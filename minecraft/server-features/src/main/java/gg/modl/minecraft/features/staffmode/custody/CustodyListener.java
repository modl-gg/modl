package gg.modl.minecraft.features.staffmode.custody;

import java.util.UUID;

public interface CustodyListener {
    void custodyResolved(UUID player);

    void belongingsTaken(UUID player);

    void staffModeRefused(UUID player, CustodyNotice notice);

    void notifyPlayer(UUID player, CustodyNotice notice);
}
