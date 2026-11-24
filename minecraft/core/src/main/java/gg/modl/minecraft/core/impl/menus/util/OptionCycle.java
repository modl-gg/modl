package gg.modl.minecraft.core.impl.menus.util;

import gg.modl.minecraft.core.locale.LocaleManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class OptionCycle {
    private final String labelKeyPrefix;
    private final List<String> ids;

    private OptionCycle(String labelKeyPrefix, List<String> ids) {
        this.labelKeyPrefix = labelKeyPrefix;
        this.ids = ids;
    }

    public static OptionCycle of(String labelKeyPrefix, String... ids) {
        if (ids.length == 0) throw new IllegalArgumentException("An option cycle needs at least one option");
        return new OptionCycle(labelKeyPrefix, Collections.unmodifiableList(Arrays.asList(ids)));
    }

    public List<String> ids() {
        return ids;
    }

    public String first() {
        return ids.get(0);
    }

    public String next(String current) {
        return ids.get((ids.indexOf(current) + 1) % ids.size());
    }

    public String label(LocaleManager locale, String id) {
        return locale.getMessage(labelKeyPrefix + id);
    }
}
