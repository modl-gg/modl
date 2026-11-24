package gg.modl.backend.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.player.controller.MinecraftPlayerProtoMapper;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentDataView;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.dto.response.SimplePunishmentView;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeIndex;
import gg.modl.backend.support.ApplicationJson;
import gg.modl.proto.modl.v1.SimplePunishment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimplePunishmentViewGoldenTest {


    private PlayerStatusCalculator statusCalculator;

    @BeforeEach
    void setUp() {
        statusCalculator = mock(PlayerStatusCalculator.class);
        when(statusCalculator.getEffectiveCategory(any(Punishment.class))).thenReturn(ResolvedCategory.NONE);
    }

    @Test
    void fullyPopulatedViewSerializesLikeLegacyMapAndMapsToProto() throws Exception {
        PunishmentType type = PunishmentType.builder()
            .ordinal(6)
            .name("Chat Abuse")
            .category("Social")
            .playerDescription("Watch your language")
            .build();
        List<PunishmentType> types = List.of(type);

        Map<String, Object> data = new HashMap<>();
        data.put("reason", "spam in chat");
        PunishmentModification pardon = new PunishmentModification(
            "mod-1", "MANUAL_PARDON", Instant.ofEpochMilli(1_650_000_000_000L), "Admin", null,
            "cleared", 3_600_000L, null, null);
        Punishment punishment = punishment(6, "Mod", null, List.of(pardon), List.of(autoNote()), data);

        when(statusCalculator.getEffectiveExpiry(punishment)).thenReturn(Instant.ofEpochMilli(1_800_000_000_000L));
        when(statusCalculator.getEffectiveCategory(punishment)).thenReturn(ResolvedCategory.MUTE);

        SimplePunishmentView view = PunishmentMapper.toSimplePunishment(punishment, types, statusCalculator, Map.of());
        Map<String, Object> legacy = legacyToSimplePunishment(punishment, PunishmentTypeIndex.byOrdinal(types),
            statusCalculator, Map.of());

        assertSameJson(legacy, view);

        SimplePunishment proto = MinecraftPlayerProtoMapper.toSimplePunishment(view);
        assertEquals("Chat Abuse", proto.getType());
        assertEquals("Watch your language", proto.getDescription());
        assertEquals("p-1", proto.getId());
        assertTrue(proto.getStarted());
        assertEquals(6, proto.getOrdinal());
        assertEquals("MUTE", proto.getCategory());
        assertEquals(1_800_000_000_000L, proto.getExpiration());
        assertEquals("Mod", proto.getIssuerName());
        assertEquals(punishment.getIssued().toEpochMilli(), proto.getIssuedAt());
        assertEquals("Watch your language", proto.getPlayerDescription());
        assertFalse(proto.getSilent());
    }

    @Test
    void aSilentPunishmentReachesThePluginAsSilent() {
        Map<String, Object> data = new HashMap<>();
        data.put("silent", true);
        Punishment punishment = punishment(6, "Mod", null, List.of(), List.of(), data);

        SimplePunishmentView view = PunishmentMapper.toSimplePunishment(punishment, List.of(), statusCalculator, Map.of());

        assertTrue(view.silent());
        assertTrue(MinecraftPlayerProtoMapper.toSimplePunishment(view).getSilent());
    }

    @Test
    void minimalViewWithAbsentOptionalsSerializesLikeLegacyMap() throws Exception {
        Map<String, Object> data = new HashMap<>();
        Punishment punishment = punishment(6, "Mod", null, List.of(), List.of(autoNote()), data);

        SimplePunishmentView view = PunishmentMapper.toSimplePunishment(punishment, List.of(), statusCalculator, Map.of());
        Map<String, Object> legacy = legacyToSimplePunishment(punishment, PunishmentTypeIndex.byOrdinal(List.of()),
            statusCalculator, Map.of());

        assertSameJson(legacy, view);
        assertEquals("OTHER", view.category());
        assertEquals("No reason specified", view.description());

        SimplePunishment proto = MinecraftPlayerProtoMapper.toSimplePunishment(view);
        assertEquals(0L, proto.getExpiration());
        assertEquals("", proto.getPlayerDescription());
        assertEquals("OTHER", proto.getCategory());
    }

    @Test
    void nullToleratedModificationValuesSerializeLikeLegacyMap() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("reason", "griefing");
        PunishmentModification modification = new PunishmentModification(
            "mod-2", "SET_ALT_BLOCKING", null, null, null,
            "toggled", null, null, null);
        Punishment punishment = punishment(6, null, "staff-77", List.of(modification), List.of(), data);

        SimplePunishmentView view = PunishmentMapper.toSimplePunishment(punishment, List.of(), statusCalculator, Map.of());
        Map<String, Object> legacy = legacyToSimplePunishment(punishment, PunishmentTypeIndex.byOrdinal(List.of()),
            statusCalculator, Map.of());

        assertSameJson(legacy, view);
        assertEquals(0L, view.modifications().get(0).effectiveDuration());
        assertEquals("Unknown Staff", view.issuerName());
        assertEquals("Console", view.modifications().get(0).issuerName());
    }

    private void assertSameJson(Map<String, Object> legacy, SimplePunishmentView view) throws Exception {
        String legacyJson = ApplicationJson.MAPPER.writeValueAsString(legacy);
        String viewJson = ApplicationJson.MAPPER.writeValueAsString(view);
        assertEquals(legacyJson, viewJson);
        assertEquals(ApplicationJson.MAPPER.readTree(legacyJson), ApplicationJson.MAPPER.readTree(viewJson));
    }

    private static Map<String, Object> legacyToSimplePunishment(
        Punishment punishment,
        Map<Integer, PunishmentType> typesByOrdinal,
        PlayerStatusCalculator statusCalculator,
        Map<String, String> resolvedIssuers
    ) {
        PunishmentDataView data = punishment.data();
        Instant expires = statusCalculator.getEffectiveExpiry(punishment);

        PunishmentType punishmentType = typesByOrdinal.get(punishment.getTypeOrdinal());
        String typeName = punishmentType != null ? punishmentType.getName() : "Unknown";
        String playerDescription = punishmentType != null ? punishmentType.getPlayerDescription() : null;

        String effectiveCategory = statusCalculator.getEffectiveCategory(punishment).displayName();
        String category = effectiveCategory != null ? effectiveCategory : "OTHER";

        String reason = data.reason();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", punishment.getId());
        result.put("type", typeName);
        result.put("category", category);
        result.put("typeOrdinal", punishment.getTypeOrdinal());
        result.put("ordinal", punishment.getTypeOrdinal());
        result.put("started", punishment.getStarted() != null);
        result.put("expiration", expires != null ? expires.toEpochMilli() : null);
        boolean reasonIsTypedForThePlayer = punishment.getTypeOrdinal() <= 2 && reason != null && !reason.isBlank();
        String description = playerDescription != null ? playerDescription : "No reason specified";
        result.put("description", reasonIsTypedForThePlayer ? reason : description);
        result.put("issuerName", PunishmentMapper.resolveIssuer(punishment.getIssuerId(), punishment.getIssuerName(), resolvedIssuers));
        result.put("issuedAt", punishment.getIssued().toEpochMilli());
        result.put("playerDescription", playerDescription);
        result.put("modifications", punishment.getModifications()
            .stream().map(m -> {
                Map<String, Object> modMap = new LinkedHashMap<>();
                modMap.put("type", m.type());
                modMap.put("timestamp", m.date() != null ? m.date().toEpochMilli() : null);
                modMap.put("effectiveDuration", m.effectiveDuration() != null ? m.effectiveDuration() : 0L);
                modMap.put("issuerName", PunishmentMapper.resolveIssuer(m.issuerId(), m.issuerName(), resolvedIssuers));
                return modMap;
            }).toList());
        result.put("silent", punishment.data().silent());

        return result;
    }

    private PunishmentNote autoNote() {
        return new PunishmentNote("note-auto", "issued 1d mute", Instant.now(), "Mod", null);
    }

    private Punishment punishment(
        int typeOrdinal,
        String issuerName,
        String issuerId,
        List<PunishmentModification> modifications,
        List<PunishmentNote> notes,
        Map<String, Object> data
    ) {
        return new Punishment(
            "p-1",
            typeOrdinal,
            issuerName,
            issuerId,
            PunishmentIssuerKind.STAFF,
            Instant.ofEpochMilli(1_600_000_000_000L),
            Instant.ofEpochMilli(1_600_000_100_000L),
            new ArrayList<>(modifications),
            new ArrayList<>(notes),
            new ArrayList<>(),
            new ArrayList<>(),
            data
        );
    }
}
