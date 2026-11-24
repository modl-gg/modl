package gg.modl.minecraft.api;

import gg.modl.minecraft.api.http.response.PunishmentDetailResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentActiveStateTest {

    private static final long ONE_HOUR = 3_600_000L;
    private static final long SEVEN_DAYS = 7L * 24L * ONE_HOUR;

    @Test
    void unstartedTimedBanIsNotActive() {
        Punishment punishment = ban(null, SEVEN_DAYS);

        assertFalse(punishment.isActive());
    }

    @Test
    void unstartedTimedBanHasNoEffectiveExpiry() {
        Punishment punishment = ban(null, SEVEN_DAYS);

        assertNull(punishment.getEffectiveExpiry());
    }

    @Test
    void unstartedPermanentBanIsNotActive() {
        Punishment punishment = ban(null, null);

        assertFalse(punishment.isActive());
    }

    @Test
    void unstartedTimedMuteIsNotActive() {
        Punishment punishment = mute(null, SEVEN_DAYS);

        assertFalse(punishment.isActive());
    }

    @Test
    void startedTimedBanIsActive() {
        Punishment punishment = ban(Instant.ofEpochMilli(System.currentTimeMillis() - ONE_HOUR), SEVEN_DAYS);

        assertTrue(punishment.isActive());
    }

    @Test
    void startedTimedBanExpiryCountsFromStartNotNow() {
        Instant started = Instant.ofEpochMilli(System.currentTimeMillis() - ONE_HOUR);
        Punishment punishment = ban(started, SEVEN_DAYS);

        assertEquals(started.toEpochMilli() + SEVEN_DAYS, punishment.getEffectiveExpiry().toEpochMilli());
    }

    @Test
    void startedExpiredTimedBanIsNotActive() {
        Punishment punishment = ban(Instant.ofEpochMilli(System.currentTimeMillis() - SEVEN_DAYS - ONE_HOUR), SEVEN_DAYS);

        assertFalse(punishment.isActive());
    }

    @Test
    void startedPermanentBanIsActive() {
        Punishment punishment = ban(Instant.ofEpochMilli(System.currentTimeMillis() - ONE_HOUR), null);

        assertTrue(punishment.isActive());
        assertNull(punishment.getEffectiveExpiry());
    }

    @ParameterizedTest
    @EnumSource(value = Modification.Type.class, names = {"MANUAL_PARDON", "APPEAL_ACCEPT", "SYSTEM_PARDON", "ROLLBACK"})
    void anEndingModificationMakesThePunishmentInactive(Modification.Type type) {
        assertFalse(modified(type, Instant.now()).isActive());
    }

    @ParameterizedTest
    @EnumSource(value = Modification.Type.class, names = {"MANUAL_DURATION_CHANGE", "APPEAL_DURATION_CHANGE", "APPEAL_REJECT"})
    void otherModificationsLeaveThePunishmentActive(Modification.Type type) {
        assertTrue(modified(type, Instant.now()).isActive());
    }

    @Test
    void aRollbackIsDatedAsARollbackAndNotAsAPardon() {
        Instant rolledBack = Instant.now();
        Punishment punishment = modified(Modification.Type.ROLLBACK, rolledBack);

        assertNull(punishment.getPardonDate());
        assertEquals(rolledBack, punishment.getRollbackDate());
    }

    @Test
    void punishmentDetailRecognisesARollbackThatIsNotAPardon() {
        PunishmentDetailResponse.PunishmentDetail detail = PunishmentDetailResponse.PunishmentDetail.builder()
                .modifications(Collections.singletonList(Collections.singletonMap("type", "ROLLBACK")))
                .build();

        assertTrue(detail.hasModification(Modification.Type::endsEnforcement));
        assertFalse(detail.hasModification(Modification.Type::isPardon));
    }

    @Test
    void anAppealDurationChangeCountsFromWhenItWasMade() {
        Instant changed = Instant.now();
        Punishment punishment = withModification(Instant.ofEpochMilli(changed.toEpochMilli() - SEVEN_DAYS - ONE_HOUR),
                new Modification(Modification.Type.APPEAL_DURATION_CHANGE, "Admin", changed, ONE_HOUR));

        assertTrue(punishment.isActive());
        assertEquals(changed.toEpochMilli() + ONE_HOUR, punishment.getEffectiveExpiry().toEpochMilli());
    }

    @Test
    void anAppealDurationChangeToPermanentRemovesTheExpiry() {
        Punishment punishment = withModification(Instant.ofEpochMilli(System.currentTimeMillis() - ONE_HOUR),
                new Modification(Modification.Type.APPEAL_DURATION_CHANGE, "Admin", Instant.now(), null));

        assertNull(punishment.getEffectiveExpiry());
        assertNull(punishment.getEffectiveDuration());
    }

    @Test
    void aDurationChangeOverridesTheRecordedExpiry() {
        Instant changed = Instant.now();
        Punishment punishment = withModification(Instant.ofEpochMilli(changed.toEpochMilli() - ONE_HOUR),
                new Modification(Modification.Type.APPEAL_DURATION_CHANGE, "Admin", changed, SEVEN_DAYS));
        punishment.getDataMap().put("expires", changed.toEpochMilli() - 1);

        assertTrue(punishment.isActive());
    }

    @Test
    void anAppealRejectionDoesNotChangeTheDuration() {
        Instant started = Instant.ofEpochMilli(System.currentTimeMillis() - ONE_HOUR);
        Punishment punishment = withModification(started,
                new Modification(Modification.Type.APPEAL_REJECT, "Admin", Instant.now(), ONE_HOUR));

        assertEquals(started.toEpochMilli() + SEVEN_DAYS, punishment.getEffectiveExpiry().toEpochMilli());
    }

    @Test
    void punishmentDetailReportsTheAppealedDuration() {
        Map<String, Object> change = new HashMap<>();
        change.put("type", "APPEAL_DURATION_CHANGE");
        change.put("effectiveDuration", (double) ONE_HOUR);
        PunishmentDetailResponse.PunishmentDetail detail = PunishmentDetailResponse.PunishmentDetail.builder()
                .data(Collections.singletonMap("duration", SEVEN_DAYS))
                .modifications(Collections.singletonList(change))
                .build();

        assertEquals(ONE_HOUR, detail.getEffectiveDuration());
    }

    private static Punishment modified(Modification.Type type, Instant issued) {
        return withModification(Instant.ofEpochMilli(System.currentTimeMillis() - ONE_HOUR),
                new Modification(type, "Admin", issued, null));
    }

    private static Punishment withModification(Instant started, Modification modification) {
        Map<String, Object> data = new HashMap<>();
        data.put("duration", SEVEN_DAYS);

        return Punishment.builder()
                .typeOrdinal(PunishmentTypeClassifier.ORDINAL_MUTE)
                .started(started)
                .dataMap(data)
                .modifications(Collections.singletonList(modification))
                .build();
    }

    private static Punishment ban(Instant started, Long durationMillis) {
        return punishment(PunishmentTypeClassifier.ORDINAL_BAN, started, durationMillis);
    }

    private static Punishment mute(Instant started, Long durationMillis) {
        return punishment(PunishmentTypeClassifier.ORDINAL_MUTE, started, durationMillis);
    }

    private static Punishment punishment(int typeOrdinal, Instant started, Long durationMillis) {
        Map<String, Object> data = new HashMap<>();
        if (durationMillis != null) {
            data.put("duration", durationMillis);
        }

        return Punishment.builder()
                .typeOrdinal(typeOrdinal)
                .started(started)
                .dataMap(data)
                .build();
    }
}
