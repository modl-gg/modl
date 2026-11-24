package gg.modl.backend.settings.service;

import gg.modl.backend.settings.data.DeletedPunishmentType;
import gg.modl.backend.settings.data.PunishmentCategory;
import gg.modl.backend.settings.data.PunishmentType;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

final class PunishmentTypeNumbering {
    private int lastOrdinal;
    private int lastId;

    private PunishmentTypeNumbering(int lastOrdinal, int lastId) {
        this.lastOrdinal = lastOrdinal;
        this.lastId = lastId;
    }

    static List<PunishmentType> number(
        List<PunishmentType> proposed,
        List<PunishmentType> previous,
        Collection<DeletedPunishmentType> deleted
    ) {
        List<PunishmentType> known = Stream.concat(previous.stream(), proposed.stream()).toList();
        PunishmentTypeNumbering numbering = new PunishmentTypeNumbering(
            highest(known, deleted, PunishmentType::getOrdinal, DeletedPunishmentType::ordinal),
            highest(known, deleted, PunishmentType::getId, DeletedPunishmentType::id));
        List<PunishmentType> numbered = new ArrayList<>(proposed.size());
        for (PunishmentType type : proposed) {
            numbered.add(numbering.numberIfNew(type));
        }
        return List.copyOf(numbered);
    }

    private PunishmentType numberIfNew(PunishmentType type) {
        if (type.getOrdinal() != null) {
            return type;
        }
        return type.toBuilder().ordinal(++lastOrdinal).id(++lastId).build();
    }

    private static int highest(
        List<PunishmentType> types,
        Collection<DeletedPunishmentType> deleted,
        Function<PunishmentType, @Nullable Integer> ofType,
        Function<DeletedPunishmentType, @Nullable Integer> ofDeleted
    ) {
        int highest = PunishmentCategory.MAX_CORE_ORDINAL;
        for (PunishmentType type : types) {
            highest = higher(highest, ofType.apply(type));
        }
        for (DeletedPunishmentType type : deleted) {
            highest = higher(highest, ofDeleted.apply(type));
        }
        return highest;
    }

    private static int higher(int current, @Nullable Integer candidate) {
        return candidate != null ? Math.max(current, candidate) : current;
    }
}
