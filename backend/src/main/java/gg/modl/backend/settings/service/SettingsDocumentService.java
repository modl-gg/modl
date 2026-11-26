package gg.modl.backend.settings.service;

import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.StaleClientVersionException;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.settings.repository.SettingsRepository;
import gg.modl.backend.settings.repository.SettingsRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SettingsDocumentService {
    private static final long ABSENT_VERSION = 0L;
    private static final String STALE_VERSION_MESSAGE = "Settings were modified by another user. Reload and retry.";

    private final SettingsRepository settingsRepository;
    private final UnitOfWork unitOfWork;
    private final JsonColumns jsonColumns;

    public RawSettingsState getRawState(String type) {
        return settingsRepository.find(type).map(this::toRawState).orElseGet(RawSettingsState::absent);
    }

    public RawSettingsState updateExpecting(String type, long clientVersion, UnaryOperator<Map<String, Object>> change) {
        return unitOfWork.write(() -> {
            RawSettingsState current = getRawState(type);
            if (current.version() != clientVersion) {
                throw stale(current.version());
            }
            return write(type, current, change).orElseThrow(() -> stale(getRawState(type).version()));
        });
    }

    public RawSettingsState updateLatest(String type, UnaryOperator<Map<String, Object>> change) {
        return unitOfWork.write(() -> updateFrom(type, getRawState(type), change));
    }

    public RawSettingsState updateFrom(String type, RawSettingsState read, UnaryOperator<Map<String, Object>> change) {
        return write(type, read, change)
            .orElseThrow(() -> new ConcurrentUpdateException("The " + type + " settings changed while they were being updated"));
    }

    public void deleteState(String type) {
        unitOfWork.write(() -> {
            settingsRepository.delete(type);
        });
    }

    private Optional<RawSettingsState> write(String type, RawSettingsState current, UnaryOperator<Map<String, Object>> change) {
        String json = jsonColumns.write(change.apply(new LinkedHashMap<>(current.data())));
        if (current.exists() && jsonColumns.readMap(json).equals(current.data())) {
            return Optional.of(current);
        }
        boolean written = current.exists()
            ? settingsRepository.updateIfVersion(type, current.version(), json)
            : settingsRepository.insertIfAbsent(type, json);
        return written ? Optional.of(getRawState(type)) : Optional.empty();
    }

    private RawSettingsState toRawState(SettingsRow row) {
        return new RawSettingsState(jsonColumns.readMap(row.dataJson()), row.version(), row.updatedAt(), true);
    }

    private static StaleClientVersionException stale(long currentVersion) {
        return new StaleClientVersionException(STALE_VERSION_MESSAGE, currentVersion);
    }

    public record RawSettingsState(Map<String, Object> data, long version, @Nullable Instant updatedAt, boolean exists) {
        static RawSettingsState absent() {
            return new RawSettingsState(new LinkedHashMap<>(), ABSENT_VERSION, null, false);
        }
    }
}
