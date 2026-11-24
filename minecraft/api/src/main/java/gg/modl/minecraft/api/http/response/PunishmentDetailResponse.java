package gg.modl.minecraft.api.http.response;

import gg.modl.minecraft.api.Modification;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

@Getter @NoArgsConstructor @AllArgsConstructor
public class PunishmentDetailResponse extends StatusResponse {
    private PunishmentDetail punishment;
    private int status;

    @Override
    public boolean isSuccess() {
        return super.isSuccess() && punishment != null;
    }

    @Getter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PunishmentDetail {
        private String id, playerUuid, playerName, issuerName, issued, started, type;
        private Map<String, Object> data;
        private List<Object> modifications, notes, evidence;
        private int typeOrdinal;

        public boolean hasModification(Predicate<Modification.Type> matcher) {
            if (modifications == null) return false;
            for (Object modification : modifications) {
                Modification.Type type = typeOf(modification);
                if (type != null && matcher.test(type)) return true;
            }
            return false;
        }

        public @Nullable Long getEffectiveDuration() {
            Object duration = data != null ? data.get("duration") : null;
            if (modifications != null) {
                for (Object modification : modifications) {
                    Modification.Type type = typeOf(modification);
                    if (type != null && type.isDurationChange()) duration = ((Map<?, ?>) modification).get("effectiveDuration");
                }
            }
            if (!(duration instanceof Number)) return null;
            long millis = ((Number) duration).longValue();
            return millis > 0 ? millis : null;
        }

        private static Modification.@Nullable Type typeOf(Object modification) {
            if (!(modification instanceof Map)) return null;
            Object name = ((Map<?, ?>) modification).get("type");
            return name instanceof String ? Modification.Type.fromName((String) name) : null;
        }
    }
}
