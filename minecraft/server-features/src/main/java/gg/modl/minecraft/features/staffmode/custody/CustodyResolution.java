package gg.modl.minecraft.features.staffmode.custody;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

enum CustodyResolution {
    NOTHING_HELD,
    STILL_HELD,
    RESTORE_LOST_PLAYER_DATA,
    OBSOLETE,
    SET_ASIDE,
    MARK_WITHOUT_RECORD,
    WRONG_RECORD,
    UNREADABLE_WHILE_HELD,
    SET_UNREADABLE_ASIDE,
    UNRESOLVED;

    static CustodyResolution ofRecord(Optional<UUID> mark, Optional<CustodyRecord> stored, boolean loadedFromDisk,
                                      String markKind, BooleanSupplier hasSavedPlayerData) {
        if (!stored.isPresent()) return mark.isPresent() ? MARK_WITHOUT_RECORD : NOTHING_HELD;
        CustodyRecord record = stored.get();
        if (mark.isPresent()) return mark.get().equals(record.getId()) ? STILL_HELD : WRONG_RECORD;
        if (!loadedFromDisk) return NOTHING_HELD;
        if (record.isReleased()) return markKind.equals(record.getMarkKind()) ? OBSOLETE : SET_ASIDE;
        return hasSavedPlayerData.getAsBoolean() ? SET_ASIDE : RESTORE_LOST_PLAYER_DATA;
    }

    static CustodyResolution ofUnreadable(Optional<UUID> mark, boolean loadedFromDisk) {
        if (mark.isPresent()) return UNREADABLE_WHILE_HELD;
        return loadedFromDisk ? SET_UNREADABLE_ASIDE : UNRESOLVED;
    }
}
