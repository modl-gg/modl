package gg.modl.backend.settings.service;

import gg.modl.backend.settings.data.PunishmentType;
import java.util.List;
import java.util.Optional;

record PunishmentTypeChange(List<PunishmentType> before, List<PunishmentType> after, boolean written) {
    Optional<PunishmentType> previous(int ordinal) {
        return Optional.ofNullable(PunishmentTypeIndex.byOrdinal(before).get(ordinal));
    }

    Optional<PunishmentType> current(int ordinal) {
        return Optional.ofNullable(PunishmentTypeIndex.byOrdinal(after).get(ordinal));
    }
}
