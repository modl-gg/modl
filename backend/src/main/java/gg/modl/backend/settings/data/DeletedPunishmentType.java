package gg.modl.backend.settings.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DeletedPunishmentType(int ordinal, @Nullable Integer id, @Nullable String name, @Nullable Boolean appealable) {
    public boolean appealableAfterDeletion() {
        return appealable == null || appealable;
    }
}
