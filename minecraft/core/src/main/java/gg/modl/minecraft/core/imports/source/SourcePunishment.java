package gg.modl.minecraft.core.imports.source;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
@Builder(toBuilder = true)
public class SourcePunishment {
    private static final long LAPSED_AT_ONCE_MILLIS = 1;

    @NonNull String id;
    @NonNull PunishmentKind kind;
    @NonNull String reason;
    @Nullable String issuerName;
    long issuedAt;
    long duration;
    @Nullable Removal removal;
    boolean endedEarly;
    boolean inForce;
    boolean attributedByAddress;
    @Builder.Default @NonNull PunishmentScope scope = PunishmentScope.ACCOUNT;

    public SourcePunishment attributedToTheOnlyAccountOfItsAddress() {
        return toBuilder().scope(PunishmentScope.IP_WIDE).attributedByAddress(true).build();
    }

    public boolean blocksAltsWhenWidened() {
        return kind == PunishmentKind.BAN && scope == PunishmentScope.IP_WIDE && !attributedByAddress;
    }

    public boolean isLifted() {
        return removal != null || endedEarly;
    }

    public static long durationBetween(long issuedAt, long until) {
        if (until <= 0) return 0;
        return until > issuedAt ? until - issuedAt : LAPSED_AT_ONCE_MILLIS;
    }
}
