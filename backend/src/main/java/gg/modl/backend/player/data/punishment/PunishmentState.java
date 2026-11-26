package gg.modl.backend.player.data.punishment;

import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;

public final class PunishmentState {
    public static final Instant INACTIVE = Instant.EPOCH;
    public static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999Z");

    private static final long MAX_MILLIS = MAX.toEpochMilli();

    private PunishmentState() {
    }

    public static Instant activeUntil(Punishment punishment) {
        if (cannotBeActive(punishment)) {
            return INACTIVE;
        }
        PunishmentModification durationChange = latestDurationChange(punishment.getModifications());
        Long duration = timed(currentDuration(punishment, durationChange));
        Instant started = punishment.getStarted();
        if (duration == null || started == null) {
            return MAX;
        }
        return saturatedPlus(clockStart(started, durationChange), duration);
    }

    @Nullable
    public static Instant displayExpiry(Punishment punishment, Instant now) {
        PunishmentDataView data = punishment.data();
        if (data.asMap() == null) {
            return null;
        }
        PunishmentModification durationChange = latestDurationChange(punishment.getModifications());
        Long duration = timed(currentDuration(punishment, durationChange));
        if (duration == null) {
            return null;
        }
        Instant started = punishment.getStarted();
        return saturatedPlus(started != null ? clockStart(started, durationChange) : now, duration);
    }

    public static boolean endsEarlierWith(Punishment punishment, @Nullable Long newDurationMillis, Instant at) {
        Long newDuration = timed(newDurationMillis);
        if (punishment.getTypeOrdinal() == Punishment.KICK_TYPE_ORDINAL || punishment.hasEndedEnforcement() || newDuration == null) {
            return false;
        }
        PunishmentModification durationChange = latestDurationChange(punishment.getModifications());
        Long currentDuration = timed(currentDuration(punishment, durationChange));
        if (currentDuration == null) {
            return true;
        }
        Instant started = punishment.getStarted();
        if (started == null || punishment.data().isUnstarted()) {
            return newDuration < currentDuration;
        }
        Instant currentEnd = saturatedPlus(clockStart(started, durationChange), currentDuration);
        Instant newEnd = saturatedPlus(laterOf(started, at), newDuration);
        return newEnd.isBefore(currentEnd);
    }

    @Nullable
    private static Long currentDuration(Punishment punishment, @Nullable PunishmentModification durationChange) {
        return durationChange != null ? durationChange.effectiveDuration() : punishment.data().duration();
    }

    private static Instant clockStart(Instant started, @Nullable PunishmentModification durationChange) {
        return durationChange != null ? laterOf(started, durationChange.date()) : started;
    }

    private static Instant laterOf(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    public static Instant saturatedPlus(Instant base, long durationMillis) {
        long baseMillis = base.toEpochMilli();
        if (baseMillis >= MAX_MILLIS || durationMillis > MAX_MILLIS - baseMillis) {
            return MAX;
        }
        return Instant.ofEpochMilli(baseMillis + durationMillis);
    }

    private static boolean cannotBeActive(Punishment punishment) {
        return punishment.getTypeOrdinal() == Punishment.KICK_TYPE_ORDINAL
            || punishment.data().isUnstarted()
            || punishment.hasEndedEnforcement();
    }

    private static @Nullable Long timed(@Nullable Long durationMillis) {
        return durationMillis != null && durationMillis > 0 ? durationMillis : null;
    }

    @Nullable
    private static PunishmentModification latestDurationChange(List<PunishmentModification> modifications) {
        PunishmentModification latest = null;
        for (PunishmentModification modification : modifications) {
            if (PunishmentModificationType.isDurationChange(modification.type())) {
                latest = modification;
            }
        }
        return latest;
    }
}
