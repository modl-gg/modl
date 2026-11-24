package gg.modl.minecraft.features.staffmode.custody;

import lombok.Value;

import java.util.UUID;

@Value
public class CustodyRecord {
    UUID id;
    boolean released;
    String markKind;
    String gameMode;
    String belongings;

    static CustodyRecord held(UUID id, String markKind, Belongings belongings) {
        return new CustodyRecord(id, false, markKind, belongings.gameMode(), belongings.encoded());
    }

    CustodyRecord release() {
        return new CustodyRecord(id, true, markKind, gameMode, belongings);
    }
}
