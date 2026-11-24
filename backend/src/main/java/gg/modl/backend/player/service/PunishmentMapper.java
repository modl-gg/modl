package gg.modl.backend.player.service;

import gg.modl.backend.player.data.punishment.PlayerFacingReason;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentDataView;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.dto.response.PunishmentView;
import gg.modl.backend.player.dto.response.SimplePunishmentView;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeIndex;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PunishmentMapper {

    private PunishmentMapper() {}

    public static PunishmentView toPunishmentView(Punishment punishment, List<PunishmentType> punishmentTypes) {
        return toPunishmentView(punishment, PunishmentTypeIndex.byOrdinal(punishmentTypes), Collections.emptyMap());
    }

    public static PunishmentView toPunishmentView(Punishment punishment, List<PunishmentType> punishmentTypes, Map<String, String> resolvedIssuers) {
        return toPunishmentView(punishment, PunishmentTypeIndex.byOrdinal(punishmentTypes), resolvedIssuers);
    }

    public static PunishmentView toPunishmentView(Punishment punishment, Map<Integer, PunishmentType> typesByOrdinal, Map<String, String> resolvedIssuers) {
        return toPunishmentView(punishment, PunishmentTypeLookup.ofLiveTypes(typesByOrdinal), resolvedIssuers);
    }

    public static PunishmentView toPunishmentView(Punishment punishment, PunishmentTypeLookup types, Map<String, String> resolvedIssuers) {
        int ordinal = punishment.getTypeOrdinal();
        String typeName = types.name(ordinal);

        Map<String, Object> punishmentData = punishment.data().asMap();
        Map<String, Object> dataWithTypeName = punishmentData != null ?
                                               new LinkedHashMap<>(punishmentData) : new LinkedHashMap<>();
        if (!PunishmentTypeLookup.UNKNOWN_NAME.equals(typeName)) {
            dataWithTypeName.put("typeName", typeName);
        }

        List<Map<String, Object>> modifications = punishment.getModifications()
            .stream()
            .map(m -> {
                Map<String, Object> mod = new LinkedHashMap<>();
                mod.put("id", m.id());
                mod.put("type", m.type());
                mod.put("date", m.date());
                mod.put("issuerName", resolveIssuer(m.issuerId(), m.issuerName(), resolvedIssuers));
                mod.put("effectiveDuration", m.effectiveDuration());
                mod.put("data", m.data());
                return mod;
            }).toList();

        List<Map<String, Object>> notes = punishment.getNotes()
            .stream()
            .map(n -> {
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("id", n.id());
                note.put("text", n.text());
                note.put("issuerName", resolveIssuer(n.issuerId(), n.issuerName(), resolvedIssuers));
                note.put("date", n.date());
                return note;
            }).toList();

        List<Map<String, Object>> evidence = punishment.getEvidence()
            .stream()
            .map(e -> {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("text", e.text());
                ev.put("url", e.url());
                ev.put("type", e.type());
                ev.put("uploadedBy", resolveIssuer(e.uploadedById(), e.uploadedBy(), resolvedIssuers));
                ev.put("uploadedAt", e.uploadedAt());
                ev.put("fileName", e.fileName());
                ev.put("fileType", e.fileType());
                ev.put("fileSize", e.fileSize());
                return ev;
            }).toList();

        return new PunishmentView(
            punishment.getId(),
            resolveIssuer(punishment.getIssuerId(), punishment.getIssuerName(), resolvedIssuers),
            punishment.getIssued(),
            punishment.getStarted(),
            ordinal,
            typeName,
            modifications,
            notes,
            evidence,
            punishment.getAttachedTicketIds(),
            dataWithTypeName,
            null,
            null
        );
    }

    public static String resolveIssuer(@Nullable String issuerId, @Nullable String issuerName, Map<String, String> resolvedIssuers) {
        String resolved = issuerId != null ? resolvedIssuers.get(issuerId) : null;
        if (resolved != null) {
            return resolved;
        }
        if (issuerName != null) {
            return issuerName;
        }
        return issuerId != null ? "Unknown Staff" : "Console";
    }

    static @Nullable String storedName(@Nullable String issuerId, @Nullable String issuerName) {
        return issuerId != null ? null : issuerName;
    }

    public static String offenseDisplayStatus(String offenseLevel) {
        return switch (offenseLevel) {
            case "first" -> "low";
            default -> offenseLevel;
        };
    }

    public static SimplePunishmentView toSimplePunishment(Punishment punishment, List<PunishmentType> types, PlayerStatusCalculator statusCalculator) {
        return toSimplePunishment(punishment, PunishmentTypeIndex.byOrdinal(types), statusCalculator, Collections.emptyMap());
    }

    public static SimplePunishmentView toSimplePunishment(Punishment punishment, List<PunishmentType> types, PlayerStatusCalculator statusCalculator, Map<String, String> resolvedIssuers) {
        return toSimplePunishment(punishment, PunishmentTypeIndex.byOrdinal(types), statusCalculator, resolvedIssuers);
    }

    public static SimplePunishmentView toSimplePunishment(Punishment punishment, Map<Integer, PunishmentType> typesByOrdinal, PlayerStatusCalculator statusCalculator, Map<String, String> resolvedIssuers) {
        return toSimplePunishment(punishment, PunishmentTypeLookup.ofLiveTypes(typesByOrdinal), statusCalculator, resolvedIssuers);
    }

    public static SimplePunishmentView toSimplePunishment(Punishment punishment, PunishmentTypeLookup types, PlayerStatusCalculator statusCalculator, Map<String, String> resolvedIssuers) {
        PunishmentDataView data = punishment.data();
        Instant expires = statusCalculator.getEffectiveExpiry(punishment);

        PunishmentType punishmentType = types.live(punishment.getTypeOrdinal());

        String typeName = types.name(punishment.getTypeOrdinal());
        String playerDescription = punishmentType != null ? punishmentType.getPlayerDescription() : null;

        ResolvedCategory effectiveCategory = statusCalculator.getEffectiveCategory(punishment);
        String category = effectiveCategory.isEnforced() ? effectiveCategory.name() : "OTHER";

        String reason = PlayerFacingReason.of(punishment.getTypeOrdinal(), data.reason(), playerDescription);
        List<SimplePunishmentView.Modification> modifications = punishment.getModifications()
            .stream()
            .map(m -> new SimplePunishmentView.Modification(
                m.type(),
                m.date() != null ? m.date().toEpochMilli() : null,
                m.effectiveDuration() != null ? m.effectiveDuration() : 0L,
                resolveIssuer(m.issuerId(), m.issuerName(), resolvedIssuers)
            ))
            .toList();

        return new SimplePunishmentView(
            punishment.getId(),
            typeName,
            category,
            punishment.getTypeOrdinal(),
            punishment.getTypeOrdinal(),
            punishment.getStarted() != null,
            expires != null ? expires.toEpochMilli() : null,
            reason,
            resolveIssuer(punishment.getIssuerId(), punishment.getIssuerName(), resolvedIssuers),
            punishment.getIssued().toEpochMilli(),
            playerDescription,
            modifications,
            data.silent()
        );
    }

    public static String formatDuration(long durationMs, boolean isPermanent) {
        if (isPermanent || durationMs < 0) {
            return "Permanent";
        }
        if (durationMs == 0) {
            return "Instant";
        }

        long totalSeconds = durationMs / 1000;
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d");
        }
        if (hours > 0) {
            if (!sb.isEmpty()) {
                sb.append(" ");
            }
            sb.append(hours).append("h");
        }
        if (minutes > 0) {
            if (!sb.isEmpty()) {
                sb.append(" ");
            }
            sb.append(minutes).append("m");
        }
        if (seconds > 0 && days == 0) {
            if (!sb.isEmpty()) {
                sb.append(" ");
            }
            sb.append(seconds).append("s");
        }

        return sb.isEmpty() ? "0s" : sb.toString();
    }
}
