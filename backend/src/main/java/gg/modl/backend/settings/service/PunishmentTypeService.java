package gg.modl.backend.settings.service;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PunishmentTypeService {
    private final PunishmentTypeCatalog catalog;

    public List<PunishmentType> getPunishmentTypes(Server server) {
        return catalog.snapshot().types();
    }

    public long calculateDurationMillis(
        Server server,
        int ordinal,
        String severity,
        String offenseLevel
    ) {
        return getPunishmentTypeByOrdinal(server, ordinal)
            .map(type -> type.getDurationMillis(
                severity != null ? severity : "regular",
                offenseLevel != null ? offenseLevel : "first"
            ))
            .orElse(0L);
    }

    public Optional<PunishmentType> getPunishmentTypeByOrdinal(Server server, int ordinal) {
        return catalog.snapshot().find(ordinal);
    }

    public String getPunishmentTypeName(Server server, int ordinal) {
        return catalog.snapshot().nameOf(ordinal);
    }

    public boolean isAppealable(Server server, int ordinal) {
        return catalog.snapshot().isAppealable(ordinal);
    }

    public int getPointsForPunishment(Server server, int ordinal, String severity) {
        return getPunishmentTypeByOrdinal(server, ordinal)
            .map(type -> type.getPointsForSeverity(severity != null ? severity : "regular"))
            .orElse(0);
    }
}
