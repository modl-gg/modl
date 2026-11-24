package gg.modl.backend.settings.repository;

import java.time.Instant;

public record SettingsRow(String type, String dataJson, long version, Instant updatedAt) {
}
