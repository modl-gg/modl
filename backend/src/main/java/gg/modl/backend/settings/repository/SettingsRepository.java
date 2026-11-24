package gg.modl.backend.settings.repository;

import java.util.Optional;

public interface SettingsRepository {
    Optional<SettingsRow> find(String type);

    boolean insertIfAbsent(String type, String json);

    boolean updateIfVersion(String type, long expectedVersion, String json);

    void upsert(String type, String json);

    boolean delete(String type);
}
