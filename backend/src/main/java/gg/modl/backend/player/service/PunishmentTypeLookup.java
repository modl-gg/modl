package gg.modl.backend.player.service;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeIndex;
import gg.modl.backend.settings.service.PunishmentTypeService;
import org.jspecify.annotations.Nullable;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

public record PunishmentTypeLookup(Map<Integer, PunishmentType> liveByOrdinal, IntFunction<String> names, IntPredicate appealable) {
    static final String UNKNOWN_NAME = "Unknown";

    public static PunishmentTypeLookup of(Server server, PunishmentTypeService service) {
        Map<Integer, PunishmentType> live = PunishmentTypeIndex.byOrdinal(service.getPunishmentTypes(server));
        return new PunishmentTypeLookup(
            live,
            ordinal -> service.getPunishmentTypeName(server, ordinal),
            ordinal -> live.containsKey(ordinal) ? live.get(ordinal).isAppealable() : service.isAppealable(server, ordinal));
    }

    static PunishmentTypeLookup ofLiveTypes(Map<Integer, PunishmentType> liveByOrdinal) {
        return new PunishmentTypeLookup(liveByOrdinal, ordinal -> {
            PunishmentType type = liveByOrdinal.get(ordinal);
            return type != null && type.getName() != null ? type.getName() : UNKNOWN_NAME;
        }, ordinal -> {
            PunishmentType type = liveByOrdinal.get(ordinal);
            return type != null && type.isAppealable();
        });
    }

    @Nullable
    PunishmentType live(int ordinal) {
        return liveByOrdinal.get(ordinal);
    }

    boolean isAppealable(int ordinal) {
        return appealable.test(ordinal);
    }

    String name(int ordinal) {
        return names.apply(ordinal);
    }
}
