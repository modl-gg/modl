package gg.modl.backend.player.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.player.dto.response.PunishmentView;
import gg.modl.backend.player.service.PlayerLookupService;
import gg.modl.proto.modl.v1.PunishmentResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MinecraftPlayerProtoMapperTest {
    private static final Instant ISSUED = Instant.ofEpochMilli(1_777_777_777_000L);
    private static final Instant ROLLED_BACK = ISSUED.plusSeconds(60);

    @Test
    void anAccountPunishmentCarriesItsActiveFlagAndItsModifications() {
        PunishmentView enforced = view(Map.of(PlayerLookupService.ACTIVE_DATA_KEY, true), List.of());
        PunishmentView rolledBack = view(Map.of(PlayerLookupService.ACTIVE_DATA_KEY, false),
            List.of(Map.of("id", "modification", "type", "ROLLBACK", "date", ROLLED_BACK, "issuerName", "Admin")));

        List<PunishmentResponse> punishments = MinecraftPlayerProtoMapper
            .toPlayerProfileResponse(Map.of("punishments", List.of(enforced, rolledBack)))
            .getProfile()
            .getPunishmentsList();

        assertTrue(punishments.getFirst().getActive());
        assertEquals(0, punishments.getFirst().getModificationsCount());
        assertFalse(punishments.get(1).getActive());
        assertEquals("ROLLBACK", punishments.get(1).getModifications(0).getType());
        assertEquals(ROLLED_BACK.toEpochMilli(), punishments.get(1).getModifications(0).getDate());
    }

    @Test
    void anAccountPunishmentCarriesTheFieldsThePluginReads() {
        PunishmentView view = view(
            Map.of(
                PlayerLookupService.ACTIVE_DATA_KEY, false,
                PlayerLookupService.APPEALABLE_DATA_KEY, true,
                PlayerLookupService.EXPIRES_DATA_KEY, 1_800_000_000_000L,
                "reason", "x-ray",
                "severity", "regular",
                "status", "Unstarted",
                "altBlocking", true,
                "wipeAfterExpiry", true,
                "enforcementCategory", "BAN"),
            List.of());

        PunishmentResponse response = account(view);

        assertTrue(response.getIsAppealable());
        assertEquals(1_800_000_000_000L, response.getExpires());
        assertEquals("x-ray", response.getReason());
        assertEquals("regular", response.getSeverity());
        assertEquals("Unstarted", response.getStatus());
        assertTrue(response.getAltBlocking());
        assertTrue(response.getStatWiping());
        assertEquals("BAN", response.getEffectiveCategory());
    }

    @Test
    void absentValuesStayUnset() {
        PunishmentResponse response = account(view(Map.of("enforcementCategory", "NONE"), List.of()));

        assertFalse(response.hasExpires());
        assertFalse(response.hasReason());
        assertFalse(response.hasSeverity());
        assertFalse(response.hasStatus());
        assertFalse(response.hasEffectiveCategory());
        assertFalse(response.getIsAppealable());
        assertFalse(response.getAltBlocking());
        assertFalse(response.getStatWiping());
    }

    @Test
    void anAccountPunishmentCarriesItsNotesAndEvidenceLikeTheHistoryPages() {
        PunishmentView view = new PunishmentView("punishment", "Mod", ISSUED, ISSUED, 2, "Ban", List.of(),
            List.of(Map.of("id", "note", "text", "staff only", "date", ROLLED_BACK, "issuerName", "Admin")),
            List.of(Map.of("type", "url", "url", "https://example.com/evidence.png", "uploadedBy", "Admin",
                "uploadedAt", ROLLED_BACK)),
            List.of(), Map.of(), null, null);

        PunishmentResponse response = account(view);

        assertEquals("staff only", response.getNotes(0).getText());
        assertEquals("Admin", response.getNotes(0).getIssuerName());
        assertEquals(ROLLED_BACK.toEpochMilli(), response.getNotes(0).getDate());
        assertEquals("https://example.com/evidence.png", response.getEvidence(0).getUrl());
        assertEquals(ROLLED_BACK.toEpochMilli(), response.getEvidence(0).getUploadedAt());
    }

    @Test
    void modificationDetailsStayOutOfTheAccount() {
        PunishmentView view = new PunishmentView("punishment", "Mod", ISSUED, ISSUED, 2, "Ban",
            List.of(Map.of("id", "modification", "type", "MANUAL_PARDON", "date", ROLLED_BACK, "issuerName", "Admin",
                "issuerId", "staff-1", "reason", "internal", "data", Map.of("secret", "detail"))),
            List.of(), List.of(), List.of(), Map.of(), null, null);

        PunishmentResponse response = account(view);

        assertFalse(response.hasReason());
        assertFalse(response.getModifications(0).hasIssuerName());
        assertFalse(response.getModifications(0).hasIssuerId());
        assertFalse(response.getModifications(0).hasData());
        assertEquals("", response.getModifications(0).getReason());
    }

    private static PunishmentResponse account(PunishmentView view) {
        return MinecraftPlayerProtoMapper.toPlayerProfileResponse(Map.of("punishments", List.of(view)))
            .getProfile()
            .getPunishments(0);
    }

    private static PunishmentView view(Map<String, Object> data, List<Map<String, Object>> modifications) {
        return new PunishmentView("punishment", "Mod", ISSUED, ISSUED, 2, "Ban",
            modifications, List.of(), List.of(), List.of(), data, null, null);
    }
}
