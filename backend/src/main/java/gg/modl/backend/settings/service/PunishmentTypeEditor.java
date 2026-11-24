package gg.modl.backend.settings.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.service.ServerTimestampService;
import gg.modl.backend.settings.data.DefaultPunishmentTypes;
import gg.modl.backend.settings.data.PunishmentCategory;
import gg.modl.backend.settings.data.PunishmentType;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PunishmentTypeEditor {
    private final PunishmentTypeCatalog catalog;
    private final ServerTimestampService serverTimestampService;
    private final UnitOfWork unitOfWork;

    public void seedDefaultTypes(Server server) {
        unitOfWork.write(() -> {
            announce(server, catalog.seed());
        });
    }

    public List<PunishmentType> resetToDefaults(Server server) {
        return replace(server, current -> DefaultPunishmentTypes.getAll()).after();
    }

    public PunishmentType createPunishmentType(Server server, PunishmentType newType) {
        String name = newType.getName();
        if (name == null || name.isBlank()) {
            throw new ValidationException("A punishment type name is required");
        }
        PunishmentType unnumbered = newType.toBuilder().id(null).ordinal(null).customizable(true).build();
        return replace(server, current -> {
            PunishmentTypeNames.requireUnused(current, name, null);
            return Stream.concat(current.stream(), Stream.of(unnumbered)).toList();
        }).after().getLast();
    }

    public PunishmentType updatePunishmentType(Server server, int ordinal, PunishmentType requested) {
        if (ordinal == PunishmentCategory.MIN_CORE_ORDINAL || ordinal == PunishmentCategory.MAX_CORE_ORDINAL) {
            throw new IllegalArgumentException("Kick and Blacklist punishment types cannot be configured");
        }
        return unitOfWork.write(() -> {
            PunishmentTypeChange change = catalog.replace(current -> current.stream()
                .map(type -> Objects.equals(type.getOrdinal(), ordinal) ? updated(current, type, requested) : type)
                .toList());
            PunishmentType saved = change.current(ordinal).orElseThrow(PunishmentTypeEditor::notFound);
            announce(server, change);
            return saved;
        });
    }

    public boolean deletePunishmentType(Server server, int ordinal) {
        if (ordinal <= PunishmentCategory.MAX_CORE_ORDINAL) {
            throw new IllegalArgumentException("Cannot delete core administrative punishment types");
        }
        return replace(server, current -> current.stream()
            .filter(type -> !Objects.equals(type.getOrdinal(), ordinal))
            .toList())
            .previous(ordinal)
            .isPresent();
    }

    private PunishmentTypeChange replace(Server server, Function<List<PunishmentType>, List<PunishmentType>> change) {
        return unitOfWork.write(() -> {
            PunishmentTypeChange replaced = catalog.replace(change);
            announce(server, replaced);
            return replaced;
        });
    }

    private void announce(Server server, PunishmentTypeChange change) {
        if (change.written()) {
            serverTimestampService.updatePunishmentTypesTimestamp(server);
        }
    }

    private static PunishmentType updated(List<PunishmentType> current, PunishmentType existing, PunishmentType requested) {
        if (existing.isCustomizable()) {
            String name = requested.getName();
            if (name != null && !name.isBlank() && !name.equals(existing.getName())) {
                PunishmentTypeNames.requireUnused(current, name, existing.getOrdinal());
            }
            return requested.toBuilder()
                .id(existing.getId())
                .ordinal(existing.getOrdinal())
                .customizable(true)
                .name(name == null || name.isBlank() ? existing.getName() : name)
                .build();
        }
        return existing.toBuilder()
            .staffDescription(requested.getStaffDescription())
            .playerDescription(requested.getPlayerDescription())
            .appealable(requested.getAppealable())
            .appealForm(requested.getAppealForm())
            .durations(requested.getDurations())
            .points(requested.getPoints())
            .singleSeverityPunishment(requested.getSingleSeverityPunishment())
            .singleSeverityDurations(requested.getSingleSeverityDurations())
            .singleSeverityPoints(requested.getSingleSeverityPoints())
            .canBeAltBlocking(requested.getCanBeAltBlocking())
            .canBeStatWiping(requested.getCanBeStatWiping())
            .permanentUntilSkinChange(requested.getPermanentUntilSkinChange())
            .permanentUntilUsernameChange(requested.getPermanentUntilUsernameChange())
            .build();
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Punishment type not found");
    }
}
