package gg.modl.backend.audit.service;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.player.data.punishment.PunishmentPhase;
import gg.modl.backend.player.repository.IssuerMatch;
import gg.modl.backend.player.repository.PunishmentListQuery;
import java.util.Arrays;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public record PunishmentListRequest(
    String status,
    String sort,
    String direction,
    @Nullable String staff,
    @Nullable Integer type,
    String evidence
) {
    private static final String ASCENDING = "asc";
    private static final String DESCENDING = "desc";
    private static final String EVIDENCE_ANY = "all";
    private static final String EVIDENCE_WITH = "yes";
    private static final String EVIDENCE_WITHOUT = "no";
    private static final String EVERY_PHASE = "all";

    PunishmentListQuery toQuery(@Nullable IssuerMatch issuer) {
        return new PunishmentListQuery(
            phase(),
            named(PunishmentListQuery.SortKey.class, "sort", sort),
            ascending(),
            issuer,
            type,
            evidenceFilter());
    }

    private @Nullable PunishmentPhase phase() {
        return EVERY_PHASE.equalsIgnoreCase(status) ? null : named(PunishmentPhase.class, "status", status);
    }

    private boolean ascending() {
        return switch (direction.toLowerCase(Locale.ROOT)) {
            case ASCENDING -> true;
            case DESCENDING -> false;
            default -> throw invalid("direction");
        };
    }

    private PunishmentListQuery.Evidence evidenceFilter() {
        return switch (evidence.toLowerCase(Locale.ROOT)) {
            case EVIDENCE_ANY -> PunishmentListQuery.Evidence.ANY;
            case EVIDENCE_WITH -> PunishmentListQuery.Evidence.WITH;
            case EVIDENCE_WITHOUT -> PunishmentListQuery.Evidence.WITHOUT;
            default -> throw invalid("evidence");
        };
    }

    private static <E extends Enum<E>> E named(Class<E> type, String parameter, String value) {
        return Arrays.stream(type.getEnumConstants())
            .filter(constant -> constant.name().equalsIgnoreCase(value))
            .findFirst()
            .orElseThrow(() -> invalid(parameter));
    }

    private static ValidationException invalid(String parameter) {
        return new ValidationException("Unsupported " + parameter + " value");
    }
}
