package gg.modl.backend.hostedimport.mapping;

import gg.modl.backend.settings.data.DeletedPunishmentType;
import gg.modl.backend.settings.data.PunishmentType;
import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ImportedTypes {
    private final List<PunishmentType> types;
    private final List<DeletedPunishmentType> tombstones;
    private final Map<Integer, PunishmentType> byOrdinal;
    private final boolean fromArchive;

    public ImportedTypes(List<PunishmentType> types, List<DeletedPunishmentType> tombstones, boolean fromArchive) {
        this.types = List.copyOf(types);
        this.tombstones = List.copyOf(tombstones);
        this.byOrdinal = types.stream().collect(Collectors.toMap(PunishmentType::getOrdinal, Function.identity(), (first, second) -> first));
        this.fromArchive = fromArchive;
    }

    @Nullable
    public PunishmentType byOrdinal(int ordinal) {
        return byOrdinal.get(ordinal);
    }

    public List<PunishmentType> types() {
        return types;
    }

    public List<DeletedPunishmentType> tombstones() {
        return tombstones;
    }

    public boolean fromArchive() {
        return fromArchive;
    }
}
