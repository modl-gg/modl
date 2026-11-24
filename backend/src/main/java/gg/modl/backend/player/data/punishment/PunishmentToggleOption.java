package gg.modl.backend.player.data.punishment;

import gg.modl.backend.settings.data.PunishmentType;
import org.jspecify.annotations.Nullable;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

public enum PunishmentToggleOption {
    ALT_BLOCKING(PunishmentData.ALT_BLOCKING, "alt-blocking", PunishmentDataView::setAltBlocking,
        PunishmentType::isCanBeAltBlocking),
    STAT_WIPE(PunishmentData.WIPE_AFTER_EXPIRY, "stat wipe", PunishmentDataView::setWipeAfterExpiry,
        PunishmentType::isCanBeStatWiping);

    private final String dataKey;
    private final String displayName;
    private final BiConsumer<PunishmentDataView, Boolean> mutator;
    private final Predicate<PunishmentType> offeredBy;

    PunishmentToggleOption(String dataKey, String displayName, BiConsumer<PunishmentDataView, Boolean> mutator,
                           Predicate<PunishmentType> offeredBy) {
        this.dataKey = dataKey;
        this.displayName = displayName;
        this.mutator = mutator;
        this.offeredBy = offeredBy;
    }

    public boolean isOfferedBy(PunishmentType type) {
        return !type.restrictsIdentity() && offeredBy.test(type);
    }

    public String dataKey() {
        return dataKey;
    }

    public String displayName() {
        return displayName;
    }

    public void apply(PunishmentDataView data, boolean enabled) {
        mutator.accept(data, enabled);
    }

    @Nullable
    public static PunishmentToggleOption from(String option) {
        if (option == null || option.isBlank()) {
            return null;
        }
        try {
            return PunishmentToggleOption.valueOf(option.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
