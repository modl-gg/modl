package gg.modl.backend.player.service;
import gg.modl.backend.player.dto.response.SimplePunishmentView;
import gg.modl.backend.player.dto.response.SyncDataView;
import gg.modl.backend.player.dto.response.SyncPunishmentEntry;
import gg.modl.backend.player.dto.response.SyncResult;
import gg.modl.backend.support.ApplicationJson;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SyncResultGoldenTest {


    @Test
    void syncResultWithOptionalSectionsIsTreeIdenticalToLegacyMap() throws Exception {
        SimplePunishmentView punishment = new SimplePunishmentView(
            "punishment-1", "Ban", "BAN", 2, 2, true, 1_800_000_000_000L,
            "Rule violation", "Mod", 1_600_000_000_000L, "You are banned",
            List.of(new SimplePunishmentView.Modification("MANUAL_PARDON", 1_650_000_000_000L, 3_600_000L, "Admin")), false);

        Map<String, Object> staffNotification = new LinkedHashMap<>();
        staffNotification.put("id", "ticket_1");
        staffNotification.put("type", "TICKET_CREATED");
        staffNotification.put("message", "created");
        staffNotification.put("timestamp", 1_600_000_000_000L);
        staffNotification.put("data", Map.of("ticketId", "1", "ticketUrl", "https://panel.example.com/ticket/1"));

        SyncResult view = new SyncResult("2026-01-01T00:00:00Z", new SyncDataView(
            List.of(new SyncPunishmentEntry("uuid-1", "Byteful", punishment)),
            List.of(),
            List.of(),
            List.of(),
            List.of(staffNotification),
            List.of(),
            List.of(),
            1_700_000_000_000L,
            1_700_000_001_000L,
            List.of(Map.of("minecraftUuid", "staff-uuid")),
            "https://panel.example.com"));

        Map<String, Object> legacyData = new LinkedHashMap<>();
        legacyData.put("pendingPunishments", List.of(Map.of("minecraftUuid", "uuid-1", "username", "Byteful", "punishment", punishment)));
        legacyData.put("recentlyStartedPunishments", List.of());
        legacyData.put("recentlyModifiedPunishments", List.of());
        legacyData.put("playerNotifications", List.of());
        legacyData.put("staffNotifications", List.of(staffNotification));
        legacyData.put("activeStaffMembers", List.of());
        legacyData.put("pendingStatWipes", List.of());
        legacyData.put("staffPermissionsUpdatedAt", 1_700_000_000_000L);
        legacyData.put("punishmentTypesUpdatedAt", 1_700_000_001_000L);
        legacyData.put("staff2faVerifications", List.of(Map.of("minecraftUuid", "staff-uuid")));
        legacyData.put("panelUrl", "https://panel.example.com");
        Map<String, Object> legacy = Map.of("timestamp", "2026-01-01T00:00:00Z", "data", legacyData);

        assertEquals(ApplicationJson.MAPPER.readTree(ApplicationJson.MAPPER.writeValueAsString(legacy)), ApplicationJson.MAPPER.readTree(ApplicationJson.MAPPER.writeValueAsString(view)));
    }

    @Test
    void syncResultWithAbsentOptionalSectionsOmitsThoseKeys() throws Exception {
        SyncResult view = new SyncResult("2026-01-01T00:00:00Z", new SyncDataView(
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            null, null, null, "https://panel.example.com"));

        Map<String, Object> legacyData = new LinkedHashMap<>();
        legacyData.put("pendingPunishments", List.of());
        legacyData.put("recentlyStartedPunishments", List.of());
        legacyData.put("recentlyModifiedPunishments", List.of());
        legacyData.put("playerNotifications", List.of());
        legacyData.put("staffNotifications", List.of());
        legacyData.put("activeStaffMembers", List.of());
        legacyData.put("pendingStatWipes", List.of());
        legacyData.put("panelUrl", "https://panel.example.com");
        Map<String, Object> legacy = Map.of("timestamp", "2026-01-01T00:00:00Z", "data", legacyData);

        assertEquals(ApplicationJson.MAPPER.readTree(ApplicationJson.MAPPER.writeValueAsString(legacy)), ApplicationJson.MAPPER.readTree(ApplicationJson.MAPPER.writeValueAsString(view)));
    }
}
