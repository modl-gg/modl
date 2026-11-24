package gg.modl.backend.player.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import gg.modl.backend.role.service.PermissionService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class PunishmentShorteningTest {
    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final long HOUR = Duration.ofHours(1).toMillis();
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final long PERMANENT = -1L;

    @Test
    void permanentToFiniteIsShortening() {
        assertThat(PunishmentState.endsEarlierWith(started(null, NOW.minusSeconds(60)), 365 * DAY, NOW)).isTrue();
    }

    @Test
    void finiteToPermanentIsLengthening() {
        assertThat(PunishmentState.endsEarlierWith(started(DAY, NOW.minusSeconds(60)), PERMANENT, NOW)).isFalse();
        assertThat(PunishmentState.endsEarlierWith(started(DAY, NOW.minusSeconds(60)), null, NOW)).isFalse();
    }

    @Test
    void permanentToPermanentIsNoChange() {
        assertThat(PunishmentState.endsEarlierWith(started(null, NOW.minusSeconds(60)), PERMANENT, NOW)).isFalse();
    }

    @Test
    void aStartedPunishmentIsComparedByItsEnd() {
        Punishment sevenDays = started(7 * DAY, NOW.minus(Duration.ofDays(2)));

        assertThat(PunishmentState.endsEarlierWith(sevenDays, 4 * DAY, NOW)).isTrue();
        assertThat(PunishmentState.endsEarlierWith(sevenDays, 5 * DAY, NOW)).isFalse();
        assertThat(PunishmentState.endsEarlierWith(sevenDays, 6 * DAY, NOW)).isFalse();
    }

    @Test
    void theSameEndIsNoChange() {
        Punishment punishment = started(DAY, NOW.minus(Duration.ofHours(6)));

        assertThat(PunishmentState.endsEarlierWith(punishment, 18 * HOUR, NOW)).isFalse();
        assertThat(PunishmentState.endsEarlierWith(punishment, 18 * HOUR - 1, NOW)).isTrue();
    }

    @Test
    void anExpiredPunishmentCanOnlyBeLengthened() {
        assertThat(PunishmentState.endsEarlierWith(started(HOUR, NOW.minus(Duration.ofDays(3))), 1L, NOW)).isFalse();
    }

    @Test
    void anEarlierDurationChangeSetsTheCurrentEnd() {
        Punishment punishment = started(30 * DAY, NOW.minus(Duration.ofDays(10)));
        punishment.getModifications().add(durationChange(DAY, NOW.minus(Duration.ofHours(12))));

        assertThat(PunishmentState.endsEarlierWith(punishment, 6 * HOUR, NOW)).isTrue();
        assertThat(PunishmentState.endsEarlierWith(punishment, 12 * HOUR, NOW)).isFalse();
        assertThat(PunishmentState.endsEarlierWith(punishment, 20 * DAY, NOW)).isFalse();
    }

    @Test
    void aQueuedPunishmentIsComparedByItsLength() {
        Punishment queued = notStarted(7 * DAY);
        queued.data().setStatus(PunishmentStatus.UNSTARTED);

        assertThat(PunishmentState.endsEarlierWith(queued, 6 * DAY, NOW)).isTrue();
        assertThat(PunishmentState.endsEarlierWith(queued, 7 * DAY, NOW)).isFalse();
        assertThat(PunishmentState.endsEarlierWith(queued, 8 * DAY, NOW)).isFalse();
    }

    @Test
    void aPunishmentWaitingForItsStartIsComparedByItsLength() {
        Punishment waiting = notStarted(7 * DAY);

        assertThat(PunishmentState.endsEarlierWith(waiting, 6 * DAY, NOW)).isTrue();
        assertThat(PunishmentState.endsEarlierWith(waiting, 7 * DAY, NOW)).isFalse();
    }

    @Test
    void aPunishmentThatHasNotStartedKeepsItsClockStoppedAfterADurationChange() {
        Punishment waiting = notStarted(7 * DAY);
        waiting.getModifications().add(durationChange(3 * DAY, NOW.minus(Duration.ofDays(2))));

        assertThat(PunishmentState.endsEarlierWith(waiting, 2 * DAY, NOW)).isTrue();
        assertThat(PunishmentState.endsEarlierWith(waiting, 3 * DAY, NOW)).isFalse();
        assertThat(PunishmentState.activeUntil(waiting)).isEqualTo(PunishmentState.MAX);
        assertThat(PunishmentState.displayExpiry(waiting, NOW)).isEqualTo(NOW.plus(Duration.ofDays(3)));
    }

    @Test
    void endedPunishmentsAndKicksCannotBeShortened() {
        Punishment pardoned = started(7 * DAY, NOW.minusSeconds(60));
        pardoned.getModifications().add(new PunishmentModification("m", PunishmentModificationType.MANUAL_PARDON.name(), NOW,
            "Mod", null, "", null, null, null));
        Punishment kick = started(null, NOW.minusSeconds(60));
        kick.setTypeOrdinal(0);

        assertThat(PunishmentState.endsEarlierWith(pardoned, HOUR, NOW)).isFalse();
        assertThat(PunishmentState.endsEarlierWith(kick, HOUR, NOW)).isFalse();
    }

    @Test
    void durationAloneMayOnlyLengthen() {
        PunishmentShortening durationOnly = holding(PermissionService.PUNISHMENT_MODIFY_DURATION);
        Punishment punishment = started(7 * DAY, NOW.minus(Duration.ofDays(2)));

        assertThatCode(() -> durationOnly.requireFor(punishment, 10 * DAY, NOW)).doesNotThrowAnyException();
        assertThatCode(() -> durationOnly.requireFor(punishment, PERMANENT, NOW)).doesNotThrowAnyException();
        assertThatThrownBy(() -> durationOnly.requireFor(punishment, DAY, NOW))
            .isInstanceOfSatisfying(ForbiddenException.class,
                refused -> assertThat(refused.getMachineCode()).isEqualTo(PunishmentShortening.NEEDS_PARDON));
    }

    @Test
    void durationWithPardonMayShorten() {
        PunishmentShortening both = holding(PermissionService.PUNISHMENT_MODIFY_DURATION, PermissionService.PUNISHMENT_MODIFY_PARDON);

        assertThatCode(() -> both.requireFor(started(7 * DAY, NOW.minus(Duration.ofDays(2))), DAY, NOW))
            .doesNotThrowAnyException();
    }

    @Test
    void oneShortenedPunishmentInABatchNeedsThePardon() {
        PunishmentShortening durationOnly = holding(PermissionService.PUNISHMENT_MODIFY_DURATION);
        List<Punishment> linkedBans = List.of(started(DAY, NOW.minusSeconds(60)), started(null, NOW.minusSeconds(60)));

        assertThatThrownBy(() -> durationOnly.requireForAll(linkedBans, 2 * DAY, NOW)).isInstanceOf(ForbiddenException.class);
        assertThatCode(() -> durationOnly.requireForAll(linkedBans, PERMANENT, NOW)).doesNotThrowAnyException();
    }

    private static PunishmentShortening holding(String... permissions) {
        Set<String> held = Set.of(permissions);
        return PunishmentShortening.grantedBy(() -> held::contains);
    }

    private static Punishment started(@Nullable Long duration, Instant started) {
        Punishment punishment = notStarted(duration);
        punishment.setStarted(started);
        return punishment;
    }

    private static Punishment notStarted(@Nullable Long duration) {
        Punishment punishment = new Punishment("PUNISH01", 2, "Mod", null, PunishmentIssuerKind.STAFF, NOW.minus(Duration.ofDays(30)), null,
            new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new HashMap<>());
        if (duration != null) {
            punishment.data().setDuration(duration);
        }
        return punishment;
    }

    private static PunishmentModification durationChange(long effectiveDuration, Instant at) {
        return new PunishmentModification("d", PunishmentModificationType.MANUAL_DURATION_CHANGE.name(), at, "Mod", null, "",
            effectiveDuration, null, null);
    }
}
