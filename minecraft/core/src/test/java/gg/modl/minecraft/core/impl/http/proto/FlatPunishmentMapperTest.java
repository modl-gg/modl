package gg.modl.minecraft.core.impl.http.proto;

import gg.modl.minecraft.api.Evidence;
import gg.modl.minecraft.api.Note;
import gg.modl.minecraft.api.Punishment;
import gg.modl.proto.modl.v1.Account;
import gg.modl.proto.modl.v1.PaginatedPunishmentsResponse;
import gg.modl.proto.modl.v1.PlayerProfileResponse;
import gg.modl.proto.modl.v1.PunishmentEvidence;
import gg.modl.proto.modl.v1.PunishmentListEntry;
import gg.modl.proto.modl.v1.PunishmentModification;
import gg.modl.proto.modl.v1.PunishmentNote;
import gg.modl.proto.modl.v1.PunishmentResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FlatPunishmentMapperTest {
    private static final long ONE_DAY = TimeUnit.DAYS.toMillis(1);
    private static final long SEVEN_DAYS = 7 * ONE_DAY;

    private final long now = System.currentTimeMillis();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theStatWipeFlagIsReadUnderTheKeyTheMenuUses(boolean enabled) {
        Punishment punishment = map(mute().setStatWiping(enabled));

        assertEquals(enabled, punishment.isWipeAfterExpiry());
        assertEquals(enabled, punishment.getDataMap().get(Punishment.DATA_KEY_WIPE_AFTER_EXPIRY));
    }

    @Test
    void aStartedPunishmentLastsFromItsStartUntilItExpires() {
        long started = now - ONE_DAY;
        Punishment punishment = map(mute().setStarted(started).setExpires(started + SEVEN_DAYS));

        assertEquals(SEVEN_DAYS, punishment.getEffectiveDuration());
        assertEquals(started + SEVEN_DAYS, punishment.getEffectiveExpiry().toEpochMilli());
    }

    @Test
    void anExpiredPunishmentKeepsItsDuration() {
        long started = now - 2 * SEVEN_DAYS;
        Punishment punishment = map(mute().setStarted(started).setExpires(started + SEVEN_DAYS));

        assertEquals(SEVEN_DAYS, punishment.getEffectiveDuration());
    }

    @Test
    void anUnstartedPunishmentShowsItsWholeDurationDespiteTheTimeSpentInTransit() {
        Punishment punishment = map(mute().setStatus("Unstarted").setExpires(now + SEVEN_DAYS - 2_000L));

        assertEquals(SEVEN_DAYS, punishment.getEffectiveDuration());
    }

    @Test
    void anUnstartedPunishmentUnderAMinuteIsRoundedToTheSecond() {
        Punishment punishment = map(mute().setStatus("Unstarted").setExpires(System.currentTimeMillis() + 30_200L));

        assertEquals(30_000L, punishment.getEffectiveDuration());
    }

    @Test
    void aStartedPunishmentWithoutExpiryIsPermanent() {
        Punishment punishment = map(mute().setStarted(now - ONE_DAY));

        assertNull(punishment.getEffectiveDuration());
        assertNull(punishment.getEffectiveExpiry());
    }

    @Test
    void anUnstartedPunishmentWithoutExpiryIsPermanent() {
        assertNull(map(mute().setStatus("Unstarted")).getEffectiveDuration());
    }

    @Test
    void aChangedDurationCountsFromTheChange() {
        long changed = now - ONE_DAY;
        Punishment punishment = map(mute()
                .setStarted(changed - SEVEN_DAYS)
                .setExpires(changed + 2 * ONE_DAY)
                .addModifications(durationChange(changed).setEffectiveDuration(2 * ONE_DAY)));

        assertEquals(2 * ONE_DAY, punishment.getEffectiveDuration());
        assertEquals(2 * ONE_DAY, punishment.getDuration());
        assertEquals(changed + 2 * ONE_DAY, punishment.getEffectiveExpiry().toEpochMilli());
    }

    @Test
    void aChangeToPermanentHasNoDuration() {
        Punishment punishment = map(mute().setStarted(now - SEVEN_DAYS).addModifications(durationChange(now - ONE_DAY)));

        assertNull(punishment.getEffectiveDuration());
        assertNull(punishment.getEffectiveExpiry());
    }

    @Test
    void anAccountPunishmentKeepsTheNotesEvidenceAndTicketsOfAHistoryPageEntry() {
        PunishmentNote note = PunishmentNote.newBuilder().setText("spoke to them").setDate(now).setIssuerName("Admin").build();
        PunishmentEvidence evidence = PunishmentEvidence.newBuilder().setType("url").setUrl("https://example.com/clip.png")
                .setUploadedAt(now).build();

        Punishment fromAccount = map(mute().addNotes(note).addEvidence(evidence).addAttachedTicketIds("T1"));
        Punishment fromPage = PlayerProtoMapper.toPaginatedPunishmentsResponse(PaginatedPunishmentsResponse.newBuilder()
                .addPunishments(PunishmentListEntry.newBuilder().setId("MUTE0001").setTypeOrdinal(1).setIssued(now - SEVEN_DAYS)
                        .addNotes(note).addEvidence(evidence).addAttachedTicketIds("T1"))
                .build()).getPunishments().get(0);

        assertEquals("spoke to them", fromAccount.getNotes().get(0).getText());
        assertEquals(Instant.ofEpochMilli(now), fromAccount.getNotes().get(0).getDate());
        assertEquals("https://example.com/clip.png", fromAccount.getEvidence().get(0).getDisplayText());
        assertEquals(noteTexts(fromPage), noteTexts(fromAccount));
        assertEquals(evidenceTexts(fromPage), evidenceTexts(fromAccount));
        assertEquals(fromPage.getAttachedTicketIds(), fromAccount.getAttachedTicketIds());
    }

    private static List<String> noteTexts(Punishment punishment) {
        return punishment.getNotes().stream().map(Note::getText).collect(Collectors.toList());
    }

    private static List<String> evidenceTexts(Punishment punishment) {
        return punishment.getEvidence().stream().map(Evidence::getDisplayText).collect(Collectors.toList());
    }

    private PunishmentResponse.Builder mute() {
        return PunishmentResponse.newBuilder().setId("MUTE0001").setTypeOrdinal(1).setIssued(now - SEVEN_DAYS);
    }

    private static PunishmentModification.Builder durationChange(long date) {
        return PunishmentModification.newBuilder().setType("MANUAL_DURATION_CHANGE").setDate(date);
    }

    private static Punishment map(PunishmentResponse.Builder punishment) {
        PlayerProfileResponse response = PlayerProfileResponse.newBuilder()
                .setProfile(Account.newBuilder().setMinecraftUuid(UUID.randomUUID().toString()).addPunishments(punishment))
                .build();
        return PlayerProtoMapper.toPlayerProfileResponse(response).getProfile().getPunishments().get(0);
    }
}
