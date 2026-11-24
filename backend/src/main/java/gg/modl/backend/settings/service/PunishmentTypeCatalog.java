package gg.modl.backend.settings.service;

import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.settings.data.DefaultPunishmentTypes;
import gg.modl.backend.settings.data.DeletedPunishmentType;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.repository.SettingsRepository;
import gg.modl.backend.settings.repository.SettingsRow;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

@Component
public class PunishmentTypeCatalog {
    static final String TYPES_SETTINGS_TYPE = SettingsType.PUNISHMENT_TYPES.id();
    static final String DELETED_TYPES_SETTINGS_TYPE = SettingsType.DELETED_PUNISHMENT_TYPES.id();

    private static final SettingsCodec<List<PunishmentType>> TYPES =
        SettingsCodec.of(new TypeReference<List<PunishmentType>>() {}, DefaultPunishmentTypes::getAll);
    private static final SettingsCodec<List<DeletedPunishmentType>> DELETED_TYPES =
        SettingsCodec.of(new TypeReference<List<DeletedPunishmentType>>() {}, List::of);

    private final SettingsRepository settingsRepository;
    private final UnitOfWork unitOfWork;
    private final JsonColumns jsonColumns;
    private final SettingsCache<PunishmentTypeSnapshot> cache;

    public PunishmentTypeCatalog(SettingsRepository settingsRepository, UnitOfWork unitOfWork, JsonColumns jsonColumns, Clock clock) {
        this.settingsRepository = settingsRepository;
        this.unitOfWork = unitOfWork;
        this.jsonColumns = jsonColumns;
        this.cache = new SettingsCache<>(clock);
    }

    PunishmentTypeSnapshot snapshot() {
        return cache.get(this::load);
    }

    PunishmentTypeChange replace(Function<List<PunishmentType>, List<PunishmentType>> change) {
        return unitOfWork.write(() -> replaceStored(change));
    }

    public void replaceImported(List<PunishmentType> types, List<DeletedPunishmentType> tombstones) {
        unitOfWork.write(() -> {
            write(TYPES_SETTINGS_TYPE, settingsRepository.find(TYPES_SETTINGS_TYPE), List.copyOf(types));
            write(DELETED_TYPES_SETTINGS_TYPE, settingsRepository.find(DELETED_TYPES_SETTINGS_TYPE), List.copyOf(tombstones));
            unitOfWork.afterCommit(cache::invalidate);
        });
    }

    PunishmentTypeChange seed() {
        return unitOfWork.write(() -> settingsRepository.find(TYPES_SETTINGS_TYPE)
            .map(row -> unchanged(TYPES.decode(readJson(row))))
            .orElseGet(() -> replaceStored(Function.identity())));
    }

    private PunishmentTypeSnapshot load() {
        return unitOfWork.read(() -> PunishmentTypeSnapshot.of(
            settingsRepository.find(TYPES_SETTINGS_TYPE).map(row -> TYPES.decode(readJson(row))).orElseGet(DefaultPunishmentTypes::getAll),
            settingsRepository.find(DELETED_TYPES_SETTINGS_TYPE).map(row -> DELETED_TYPES.decode(readJson(row))).orElseGet(List::of)));
    }

    private PunishmentTypeChange replaceStored(Function<List<PunishmentType>, List<PunishmentType>> change) {
        Optional<SettingsRow> typesRow = settingsRepository.find(TYPES_SETTINGS_TYPE);
        Optional<SettingsRow> deletedRow = settingsRepository.find(DELETED_TYPES_SETTINGS_TYPE);
        List<PunishmentType> before = List.copyOf(
            typesRow.map(row -> TYPES.decodeStrict(readJson(row))).orElseGet(DefaultPunishmentTypes::getAll));
        List<DeletedPunishmentType> deleted =
            deletedRow.map(row -> DELETED_TYPES.decodeStrict(readJson(row))).orElseGet(List::of);

        List<PunishmentType> after = PunishmentTypeNumbering.number(change.apply(before), before, deleted);
        if (typesRow.isPresent() && after.equals(before)) {
            return unchanged(before);
        }
        write(TYPES_SETTINGS_TYPE, typesRow, after);
        List<DeletedPunishmentType> tombstones = withRemovedTypes(deleted, before, after);
        if (deletedRow.isEmpty() || !tombstones.equals(deleted)) {
            write(DELETED_TYPES_SETTINGS_TYPE, deletedRow, tombstones);
        }
        unitOfWork.afterCommit(cache::invalidate);
        return new PunishmentTypeChange(before, after, true);
    }

    private void write(String type, Optional<SettingsRow> read, List<?> values) {
        String json = jsonColumns.write(values);
        boolean written = read
            .map(row -> settingsRepository.updateIfVersion(type, row.version(), json))
            .orElseGet(() -> settingsRepository.insertIfAbsent(type, json));
        if (!written) {
            throw new ConcurrentUpdateException("The " + type + " settings changed while they were being replaced");
        }
    }

    private static List<DeletedPunishmentType> withRemovedTypes(
        List<DeletedPunishmentType> deleted,
        List<PunishmentType> before,
        List<PunishmentType> after
    ) {
        Map<Integer, DeletedPunishmentType> byOrdinal = new TreeMap<>();
        deleted.forEach(type -> byOrdinal.put(type.ordinal(), type));
        Map<Integer, PunishmentType> remaining = PunishmentTypeIndex.byOrdinal(after);
        PunishmentTypeIndex.byOrdinal(before).forEach((ordinal, type) -> {
            if (!remaining.containsKey(ordinal)) {
                byOrdinal.put(ordinal, new DeletedPunishmentType(ordinal, type.getId(), type.getName(), type.isAppealable()));
            }
        });
        return List.copyOf(byOrdinal.values());
    }

    private static PunishmentTypeChange unchanged(List<PunishmentType> types) {
        return new PunishmentTypeChange(types, types, false);
    }

    private @Nullable Object readJson(SettingsRow row) {
        return jsonColumns.read(row.dataJson(), Object.class);
    }
}
