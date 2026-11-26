package gg.modl.backend.replay.service;

import org.jspecify.annotations.Nullable;

public record ReplayFileCheck(@Nullable String problem, int formatVersion) {
    static ReplayFileCheck invalid(String problem) {
        return new ReplayFileCheck(problem, 0);
    }

    static ReplayFileCheck valid(int formatVersion) {
        return new ReplayFileCheck(null, formatVersion);
    }
}
