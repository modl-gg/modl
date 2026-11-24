package gg.modl.backend.settings.service;

import gg.modl.backend.settings.data.DeletedPunishmentType;
import gg.modl.backend.settings.data.PunishmentType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

record PunishmentTypeSnapshot(
    List<PunishmentType> types,
    Map<Integer, PunishmentType> typesByOrdinal,
    Map<Integer, DeletedPunishmentType> deletedByOrdinal
) {
    private static final String DELETED_SUFFIX = " (deleted)";
    private static final String UNKNOWN_NAME = "Unknown";

    static PunishmentTypeSnapshot of(List<PunishmentType> types, List<DeletedPunishmentType> deleted) {
        return new PunishmentTypeSnapshot(
            List.copyOf(types),
            Map.copyOf(PunishmentTypeIndex.byOrdinal(types)),
            deleted.stream().collect(Collectors.toUnmodifiableMap(
                DeletedPunishmentType::ordinal, type -> type, (first, latest) -> latest)));
    }

    Optional<PunishmentType> find(int ordinal) {
        return Optional.ofNullable(typesByOrdinal.get(ordinal));
    }

    boolean isAppealable(int ordinal) {
        return find(ordinal)
            .map(PunishmentType::isAppealable)
            .or(() -> Optional.ofNullable(deletedByOrdinal.get(ordinal)).map(DeletedPunishmentType::appealableAfterDeletion))
            .orElse(false);
    }

    String nameOf(int ordinal) {
        return find(ordinal)
            .map(PunishmentType::getName)
            .or(() -> Optional.ofNullable(deletedByOrdinal.get(ordinal))
                .map(DeletedPunishmentType::name)
                .map(name -> name + DELETED_SUFFIX))
            .orElse(UNKNOWN_NAME);
    }
}
