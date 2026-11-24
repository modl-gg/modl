package gg.modl.minecraft.api;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

@NoArgsConstructor @AllArgsConstructor @Getter
public final class Modification {
    private @Nullable @SerializedName("type") Type type;
    private @Nullable @SerializedName("issuerName") String issuer;
    private @Nullable @SerializedName("date") Instant issued;
    private @Nullable @SerializedName("effectiveDuration") Long effectiveDuration;

    public enum Type {
        MANUAL_PARDON,
        APPEAL_ACCEPT,
        APPEAL_REJECT,
        APPEAL_DURATION_CHANGE,
        SYSTEM_PARDON,
        MANUAL_DURATION_CHANGE,
        ROLLBACK,
        SET_ALT_BLOCKING_TRUE,
        SET_ALT_BLOCKING_FALSE,
        SET_WIPING_TRUE,
        SET_WIPING_FALSE;

        public boolean isPardon() {
            return this == MANUAL_PARDON || this == APPEAL_ACCEPT || this == SYSTEM_PARDON;
        }

        public boolean isDurationChange() {
            return this == MANUAL_DURATION_CHANGE || this == APPEAL_DURATION_CHANGE;
        }

        public boolean endsEnforcement() {
            return isPardon() || this == ROLLBACK;
        }

        public static @Nullable Type fromName(@Nullable String name) {
            for (Type type : values()) {
                if (type.name().equals(name)) return type;
            }
            return null;
        }
    }
}
