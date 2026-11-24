package gg.modl.minecraft.features.staffmode.custody;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CustodyNotice {
    NOT_SAVED("not_saved"),
    KEPT_CHANGING("kept_changing"),
    UNRESOLVED("unresolved"),
    HELD("held"),
    RECORD_MISSING("record_missing");

    private final String key;

    public String localeKey() {
        return "staff_mode.custody." + key;
    }
}
