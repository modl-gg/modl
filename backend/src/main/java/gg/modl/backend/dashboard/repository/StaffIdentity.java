package gg.modl.backend.dashboard.repository;

import org.jspecify.annotations.Nullable;

public record StaffIdentity(String username, @Nullable String assignee, @Nullable String minecraftUuid) {
}
