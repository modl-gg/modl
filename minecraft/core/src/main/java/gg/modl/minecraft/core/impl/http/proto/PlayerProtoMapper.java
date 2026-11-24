package gg.modl.minecraft.core.impl.http.proto;

import gg.modl.minecraft.core.impl.http.WireTimestamps;
import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.api.Evidence;
import gg.modl.minecraft.api.IPAddress;
import gg.modl.minecraft.api.Modification;
import gg.modl.minecraft.api.Note;
import gg.modl.minecraft.api.PlayerNoteKind;
import gg.modl.minecraft.api.Punishment;
import gg.modl.minecraft.api.SimplePunishment;
import gg.modl.minecraft.api.http.request.ChatLogBatchRequest;
import gg.modl.minecraft.api.http.request.CommandLogBatchRequest;
import gg.modl.minecraft.api.http.request.CreatePlayerNoteRequest;
import gg.modl.minecraft.api.http.request.NotificationAcknowledgeRequest;
import gg.modl.minecraft.api.http.request.PardonPlayerRequest;
import gg.modl.minecraft.api.http.request.PlayerDisconnectRequest;
import gg.modl.minecraft.api.http.request.PlayerLoginRequest;
import gg.modl.minecraft.api.http.request.PlayerLookupRequest;
import gg.modl.minecraft.api.http.request.PunishmentAcknowledgeRequest;
import gg.modl.minecraft.api.http.response.LinkedAccountsResponse;
import gg.modl.minecraft.api.http.response.OnlinePlayersResponse;
import gg.modl.minecraft.api.http.response.PardonResponse;
import gg.modl.minecraft.api.http.response.PlayerGetResponse;
import gg.modl.minecraft.api.http.response.PlayerLoginResponse;
import gg.modl.minecraft.api.http.response.PlayerLookupResponse;
import gg.modl.minecraft.api.http.response.PlayerNameResponse;
import gg.modl.minecraft.api.http.response.PlayerNoteCreateResponse;
import gg.modl.minecraft.api.http.response.PlayerProfileResponse;
import gg.modl.minecraft.api.http.response.PaginatedNotesResponse;
import gg.modl.minecraft.api.http.response.PaginatedPunishmentsResponse;
import gg.modl.minecraft.api.http.response.ReportsResponse;
import gg.modl.minecraft.api.http.response.SyncResponse;
import gg.modl.proto.modl.v1.IPEntry;
import gg.modl.proto.modl.v1.NoteEntry;
import gg.modl.proto.modl.v1.PendingStatWipe;
import gg.modl.proto.modl.v1.PunishmentEvidence;
import gg.modl.proto.modl.v1.PunishmentListEntry;
import gg.modl.proto.modl.v1.PunishmentModification;
import gg.modl.proto.modl.v1.PunishmentNote;
import gg.modl.proto.modl.v1.PunishmentResponse;
import gg.modl.proto.modl.v1.ReportEntry;

import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public final class PlayerProtoMapper {
    private static final long ONE_SECOND_MILLIS = 1000L, ONE_MINUTE_MILLIS = 60 * ONE_SECOND_MILLIS;

    private static final Logger LOGGER = Logger.getLogger(PlayerProtoMapper.class.getName());

    private PlayerProtoMapper() {
    }

    public static gg.modl.proto.modl.v1.PlayerLoginRequest toProto(PlayerLoginRequest request) {
        gg.modl.proto.modl.v1.PlayerLoginRequest.Builder builder = gg.modl.proto.modl.v1.PlayerLoginRequest.newBuilder()
            .setMinecraftUuid(request.getMinecraftUuid())
            .setUsername(request.getUsername());

        if (request.getIpAddress() != null) builder.setIpAddress(request.getIpAddress());
        if (request.getSkinHash() != null) builder.setSkinHash(request.getSkinHash());
        if (request.getServerName() != null) builder.setServerName(request.getServerName());
        if (request.getServerInstanceId() != null) builder.setServerInstanceId(request.getServerInstanceId());
        return builder.build();
    }

    public static gg.modl.proto.modl.v1.PlayerDisconnectRequest toProto(PlayerDisconnectRequest request) {
        gg.modl.proto.modl.v1.PlayerDisconnectRequest.Builder builder =
            gg.modl.proto.modl.v1.PlayerDisconnectRequest.newBuilder()
                .setMinecraftUuid(request.getMinecraftUuid())
                .setSessionDurationMs(request.getSessionDurationMs());
        if (request.getServerInstanceId() != null) builder.setServerInstanceId(request.getServerInstanceId());
        return builder.build();
    }

    public static gg.modl.proto.modl.v1.UpdatePlayerServerRequest toUpdateServerRequest(
        String minecraftUuid, String serverName, @Nullable String serverInstanceId) {
        gg.modl.proto.modl.v1.UpdatePlayerServerRequest.Builder builder =
            gg.modl.proto.modl.v1.UpdatePlayerServerRequest.newBuilder()
                .setMinecraftUuid(minecraftUuid)
                .setServerName(serverName);
        if (serverInstanceId != null) builder.setServerInstanceId(serverInstanceId);
        return builder.build();
    }

    public static gg.modl.proto.modl.v1.CreatePlayerNoteRequest toProto(CreatePlayerNoteRequest request) {
        gg.modl.proto.modl.v1.CreatePlayerNoteRequest.Builder builder =
            gg.modl.proto.modl.v1.CreatePlayerNoteRequest.newBuilder()
                .setText(request.getText())
                .setKind(toProto(request.getKind()));
        if (request.getIssuerName() != null) builder.setIssuerName(request.getIssuerName());
        request.getIssuer().writeTo(builder::setIssuerId, builder::setConsole);
        return builder.build();
    }

    private static gg.modl.proto.modl.v1.PlayerNoteKind toProto(PlayerNoteKind kind) {
        return kind == PlayerNoteKind.WARNING
            ? gg.modl.proto.modl.v1.PlayerNoteKind.PLAYER_NOTE_KIND_WARNING
            : gg.modl.proto.modl.v1.PlayerNoteKind.PLAYER_NOTE_KIND_NOTE;
    }

    public static gg.modl.proto.modl.v1.PardonPlayerRequest toProto(PardonPlayerRequest request) {
        gg.modl.proto.modl.v1.PardonPlayerRequest.Builder builder =
            gg.modl.proto.modl.v1.PardonPlayerRequest.newBuilder()
                .setPlayerName(request.getPlayerName());
        if (request.getIssuerName() != null) builder.setIssuerName(request.getIssuerName());
        request.getIssuer().writeTo(builder::setIssuerId, builder::setConsole);
        if (request.getPunishmentType() != null) builder.setPunishmentType(request.getPunishmentType());
        if (request.getReason() != null) builder.setReason(request.getReason());
        return builder.build();
    }

    public static gg.modl.proto.modl.v1.PlayerLookupRequest toProto(PlayerLookupRequest request) {
        return gg.modl.proto.modl.v1.PlayerLookupRequest.newBuilder()
            .setQuery(request.getQuery())
            .setQueryMojang(request.isQueryMojang())
            .build();
    }

    public static gg.modl.proto.modl.v1.PunishmentAcknowledgeRequest toProto(PunishmentAcknowledgeRequest request) {
        gg.modl.proto.modl.v1.PunishmentAcknowledgeRequest.Builder builder =
            gg.modl.proto.modl.v1.PunishmentAcknowledgeRequest.newBuilder()
                .setPunishmentId(request.getPunishmentId())
                .setPlayerUuid(request.getPlayerUuid())
                .setExecutedAt(request.getExecutedAt())
                .setSuccess(request.isSuccess());
        if (request.getErrorMessage() != null) builder.setErrorMessage(request.getErrorMessage());
        return builder.build();
    }

    public static gg.modl.proto.modl.v1.AcknowledgeNotificationsRequest toProto(NotificationAcknowledgeRequest request) {
        return gg.modl.proto.modl.v1.AcknowledgeNotificationsRequest.newBuilder()
            .setPlayerUuid(request.getPlayerUuid())
            .setAcknowledgedAt(request.getAcknowledgedAt())
            .addAllNotificationIds(request.getNotificationIds())
            .build();
    }

    public static gg.modl.proto.modl.v1.ChatLogBatchRequest toProto(ChatLogBatchRequest request) {
        gg.modl.proto.modl.v1.ChatLogBatchRequest.Builder builder = gg.modl.proto.modl.v1.ChatLogBatchRequest.newBuilder();
        if (request.getEntries() != null) {
            request.getEntries().forEach(entry -> builder.addEntries(gg.modl.proto.modl.v1.ChatLogEntry.newBuilder()
                .setUuid(ProtoConversions.nullToEmpty(entry.getUuid()))
                .setUsername(ProtoConversions.nullToEmpty(entry.getUsername()))
                .setMessage(ProtoConversions.nullToEmpty(entry.getMessage()))
                .setServer(ProtoConversions.nullToEmpty(entry.getServer()))
                .setTimestamp(entry.getTimestamp())
                .build()));
        }
        return builder.build();
    }

    public static gg.modl.proto.modl.v1.CommandLogBatchRequest toProto(CommandLogBatchRequest request) {
        gg.modl.proto.modl.v1.CommandLogBatchRequest.Builder builder =
            gg.modl.proto.modl.v1.CommandLogBatchRequest.newBuilder();
        if (request.getEntries() != null) {
            request.getEntries().forEach(entry -> builder.addEntries(gg.modl.proto.modl.v1.CommandLogEntry.newBuilder()
                .setUuid(ProtoConversions.nullToEmpty(entry.getUuid()))
                .setUsername(ProtoConversions.nullToEmpty(entry.getUsername()))
                .setCommand(ProtoConversions.nullToEmpty(entry.getCommand()))
                .setServer(ProtoConversions.nullToEmpty(entry.getServer()))
                .setTimestamp(entry.getTimestamp())
                .build()));
        }
        return builder.build();
    }

    public static PlayerLoginResponse toLoginResponse(gg.modl.proto.modl.v1.PlayerLoginResponse proto) {
        List<SimplePunishment> punishments = new ArrayList<>();
        proto.getActivePunishmentsList().forEach(p -> punishments.add(toSimplePunishment(p)));

        List<Map<String, Object>> notifications = new ArrayList<>();
        proto.getPendingNotificationsList().forEach(s -> notifications.add(ProtoConversions.structToMap(s)));

        List<SyncResponse.PendingStatWipe> statWipes = new ArrayList<>();
        proto.getPendingStatWipesList().forEach(w -> statWipes.add(toPendingStatWipe(w)));

        return new PlayerLoginResponse(
            punishments,
            notifications,
            statWipes,
            proto.getStatus());
    }

    public static SimplePunishment toSimplePunishment(gg.modl.proto.modl.v1.SimplePunishment proto) {
        return new SimplePunishment(
            proto.getType(),
            proto.hasCategory() ? proto.getCategory() : null,
            proto.hasExpiration() ? proto.getExpiration() : null,
            proto.getDescription(),
            proto.getId(),
            proto.hasIssuerName() ? proto.getIssuerName() : null,
            proto.hasIssuedAt() ? proto.getIssuedAt() : null,
            proto.hasPlayerDescription() ? proto.getPlayerDescription() : null,
            proto.getStarted(),
            proto.getOrdinal(),
            proto.getSilent());
    }

    private static SyncResponse.PendingStatWipe toPendingStatWipe(PendingStatWipe proto) {
        return new SyncResponse.PendingStatWipe(
            proto.getMinecraftUuid(),
            proto.getUsername(),
            proto.getPunishmentId());
    }

    public static OnlinePlayersResponse toOnlinePlayersResponse(gg.modl.proto.modl.v1.OnlinePlayersResponse proto) {
        List<OnlinePlayersResponse.OnlinePlayer> players = new ArrayList<>();
        proto.getPlayersList().forEach(p -> players.add(new OnlinePlayersResponse.OnlinePlayer(
            p.getUuid(),
            p.getUsername(),
            WireTimestamps.parse(p.getJoinedAt()),
            p.getTotalPlaytimeMs())));
        return new OnlinePlayersResponse(players, proto.getStatus(), proto.hasNextCursor() ? proto.getNextCursor() : null);
    }

    public static PlayerProfileResponse toPlayerProfileResponse(gg.modl.proto.modl.v1.PlayerProfileResponse proto) {
        return new PlayerProfileResponse(toAccount(proto.getProfile()), proto.getStatus(),
            proto.hasPunishmentCount() ? proto.getPunishmentCount() : -1,
            proto.hasNoteCount() ? proto.getNoteCount() : -1);
    }

    public static PlayerGetResponse toPlayerGetResponse(gg.modl.proto.modl.v1.PlayerGetResponse proto) {
        return new PlayerGetResponse(proto.getMessage(), toAccount(proto.getPlayer()), proto.getStatus());
    }

    public static PlayerNameResponse toPlayerNameResponse(gg.modl.proto.modl.v1.PlayerNameResponse proto) {
        return new PlayerNameResponse(proto.getMessage(), toAccount(proto.getPlayer()), proto.getStatus());
    }

    public static PlayerNoteCreateResponse toPlayerNoteCreateResponse(gg.modl.proto.modl.v1.PlayerNoteCreateResponse proto) {
        return new PlayerNoteCreateResponse(proto.getMessage(), proto.getStatus());
    }

    public static PlayerLookupResponse toPlayerLookupResponse(gg.modl.proto.modl.v1.PlayerLookupResponse proto) {
        return new PlayerLookupResponse(proto.getMessage(), toLookupData(proto.getData()), proto.getStatus());
    }

    public static LinkedAccountsResponse toLinkedAccountsResponse(gg.modl.proto.modl.v1.LinkedAccountsResponse proto) {
        List<Account> accounts = new ArrayList<>();
        proto.getLinkedAccountsList().forEach(a -> accounts.add(toAccount(a)));
        return new LinkedAccountsResponse(accounts, proto.getStatus(),
            proto.hasTotalCount() ? proto.getTotalCount() : -1, proto.getPage(), proto.getHasMore(), proto.getTruncated());
    }

    public static PaginatedPunishmentsResponse toPaginatedPunishmentsResponse(
        gg.modl.proto.modl.v1.PaginatedPunishmentsResponse proto) {
        List<Punishment> punishments = new ArrayList<>();
        proto.getPunishmentsList().forEach(p -> punishments.add(toPunishmentFromListEntry(p)));
        return new PaginatedPunishmentsResponse(punishments, proto.getTotalCount(), proto.getPage(),
            proto.getHasMore(), proto.getStatus());
    }

    public static PaginatedNotesResponse toPaginatedNotesResponse(gg.modl.proto.modl.v1.PaginatedNotesResponse proto) {
        List<Note> notes = new ArrayList<>();
        proto.getNotesList().forEach(n -> notes.add(toNote(n)));
        return new PaginatedNotesResponse(notes, proto.getTotalCount(), proto.getPage(),
            proto.getHasMore(), proto.getStatus());
    }

    public static ReportsResponse toReportsResponse(gg.modl.proto.modl.v1.ReportsResponse proto) {
        List<ReportsResponse.Report> reports = new ArrayList<>();
        proto.getReportsList().forEach(r -> reports.add(toReport(r)));
        return new ReportsResponse(reports, proto.getStatus());
    }

    public static PardonResponse toPardonResponse(gg.modl.proto.modl.v1.PardonResponse proto) {
        return new PardonResponse(proto.getMessage(), proto.getStatus(), proto.getPardonedCount(), proto.getSuccess());
    }

    private static Account toAccount(gg.modl.proto.modl.v1.Account proto) {
        List<Account.Username> usernames = new ArrayList<>();
        proto.getUsernamesList().forEach(u -> usernames.add(
            new Account.Username(u.getUsername(), WireTimestamps.parse(u.getDate()))));

        List<Note> notes = new ArrayList<>();
        proto.getNotesList().forEach(n -> notes.add(toNote(n)));

        List<IPAddress> ipList = new ArrayList<>();
        proto.getIpAddressesList().forEach(ip -> ipList.add(toIpAddress(ip)));

        List<Punishment> punishments = new ArrayList<>();
        proto.getPunishmentsList().forEach(p -> punishments.add(toPunishmentFromFlatResponse(p)));

        List<Map<String, Object>> notifications = new ArrayList<>();
        proto.getPendingNotificationsList().forEach(s -> notifications.add(ProtoConversions.structToMap(s)));

        Map<String, Object> data = proto.hasData() ? ProtoConversions.structToMap(proto.getData()) : null;

        return new Account(
            proto.getId(),
            parseAccountUuid(proto.getMinecraftUuid()),
            usernames,
            notes,
            ipList,
            punishments,
            notifications,
            data);
    }

    private static IPAddress toIpAddress(IPEntry proto) {
        List<Instant> logins = new ArrayList<>();
        proto.getLoginsList().forEach(login -> {
            Instant parsed = WireTimestamps.parse(login);
            if (parsed != null) logins.add(parsed);
        });
        return new IPAddress(
            proto.hasIpAddress() ? proto.getIpAddress() : null,
            proto.hasCountry() ? proto.getCountry() : null,
            proto.hasRegion() ? proto.getRegion() : null,
            proto.hasAsn() ? proto.getAsn() : null,
            WireTimestamps.parse(proto.getFirstLogin()),
            logins,
            proto.getProxy(),
            proto.getHosting());
    }

    static Note toNote(NoteEntry proto) {
        return new Note(
            proto.getText(),
            WireTimestamps.parse(proto.getDate()),
            ProtoConversions.emptyToNull(proto.getIssuerName()),
            ProtoConversions.emptyToNull(proto.getIssuerId()),
            proto.getKind() == gg.modl.proto.modl.v1.PlayerNoteKind.PLAYER_NOTE_KIND_WARNING
                ? PlayerNoteKind.WARNING
                : PlayerNoteKind.NOTE);
    }

    private static Punishment toPunishmentFromFlatResponse(PunishmentResponse proto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("active", proto.getActive());
        data.put("isAppealable", proto.getIsAppealable());
        if (proto.hasReason()) data.put("reason", proto.getReason());
        if (proto.hasSeverity()) data.put("severity", proto.getSeverity());
        if (proto.hasStatus()) data.put("status", proto.getStatus());
        if (proto.hasExpires()) data.put("expires", proto.getExpires());
        if (proto.hasAltBlocking()) data.put("altBlocking", proto.getAltBlocking());
        if (proto.hasStatWiping()) data.put(Punishment.DATA_KEY_WIPE_AFTER_EXPIRY, proto.getStatWiping());
        if (proto.hasEffectiveCategory()) data.put("effectiveCategory", proto.getEffectiveCategory());
        if (proto.hasPlayerUuid()) data.put("playerUuid", proto.getPlayerUuid());
        if (proto.hasPlayerUsername()) data.put("playerUsername", proto.getPlayerUsername());
        if (!proto.getType().isEmpty()) data.put("typeName", proto.getType());

        Punishment punishment = Punishment.builder()
            .id(proto.getId())
            .issuerName(proto.getIssuerName())
            .issued(ProtoConversions.instantFromMillis(proto.getIssued()))
            .started(proto.hasStarted() ? ProtoConversions.instantFromMillis(proto.getStarted()) : null)
            .typeOrdinal(proto.getTypeOrdinal())
            .modifications(toModifications(proto.getModificationsList()))
            .notes(toNotes(proto.getNotesList()))
            .evidence(toEvidenceList(proto.getEvidenceList()))
            .attachedTicketIds(new ArrayList<>(proto.getAttachedTicketIdsList()))
            .dataMap(data)
            .build();
        if (proto.hasExpires()) data.put("duration", durationUntil(proto.getExpires(), punishment.getDurationStart()));
        return punishment;
    }

    private static long durationUntil(long expires, @Nullable Instant start) {
        if (start != null) return expires - start.toEpochMilli();
        long remaining = expires - System.currentTimeMillis();
        long granularity = remaining < ONE_MINUTE_MILLIS ? ONE_SECOND_MILLIS : ONE_MINUTE_MILLIS;
        return Math.round((double) remaining / granularity) * granularity;
    }

    private static Punishment toPunishmentFromListEntry(PunishmentListEntry proto) {
        Map<String, Object> data = proto.hasData()
            ? new LinkedHashMap<>(ProtoConversions.structToMap(proto.getData()))
            : new LinkedHashMap<>();
        if (!proto.getType().isEmpty()) data.putIfAbsent("typeName", proto.getType());

        return Punishment.builder()
            .id(proto.getId())
            .issuerName(proto.getIssuerName())
            .issued(ProtoConversions.instantFromMillis(proto.getIssued()))
            .started(proto.hasStarted() ? ProtoConversions.instantFromMillis(proto.getStarted()) : null)
            .typeOrdinal(proto.hasTypeOrdinal() ? proto.getTypeOrdinal() : null)
            .modifications(toModifications(proto.getModificationsList()))
            .notes(toNotes(proto.getNotesList()))
            .evidence(toEvidenceList(proto.getEvidenceList()))
            .attachedTicketIds(new ArrayList<>(proto.getAttachedTicketIdsList()))
            .dataMap(data)
            .build();
    }

    private static List<Note> toNotes(List<PunishmentNote> protos) {
        List<Note> notes = new ArrayList<>();
        protos.forEach(proto -> notes.add(toNote(proto)));
        return notes;
    }

    private static List<Evidence> toEvidenceList(List<PunishmentEvidence> protos) {
        List<Evidence> evidence = new ArrayList<>();
        protos.forEach(proto -> evidence.add(toEvidence(proto)));
        return evidence;
    }

    private static List<Modification> toModifications(List<PunishmentModification> protos) {
        List<Modification> modifications = new ArrayList<>();
        protos.forEach(proto -> modifications.add(toModification(proto)));
        return modifications;
    }

    public static Modification toModification(PunishmentModification proto) {
        Modification.Type type = parseModificationType(proto.getType());
        return new Modification(
            type,
            proto.hasIssuerName() ? proto.getIssuerName() : null,
            ProtoConversions.instantFromMillis(proto.getDate()),
            proto.hasEffectiveDuration() ? proto.getEffectiveDuration() : null);
    }

    static Note toNote(PunishmentNote proto) {
        return new Note(
            proto.getText(),
            ProtoConversions.instantFromMillis(proto.getDate()),
            proto.hasIssuerName() ? proto.getIssuerName() : null,
            proto.hasIssuerId() ? proto.getIssuerId() : null);
    }

    public static Evidence toEvidence(PunishmentEvidence proto) {
        return new Evidence(
            proto.hasText() ? proto.getText() : null,
            proto.hasUrl() ? proto.getUrl() : null,
            proto.getType(),
            proto.hasUploadedBy() ? proto.getUploadedBy() : null,
            ProtoConversions.instantFromMillis(proto.getUploadedAt()),
            proto.hasFileName() ? proto.getFileName() : null,
            proto.hasFileType() ? proto.getFileType() : null,
            proto.hasFileSize() ? proto.getFileSize() : null);
    }

    private static Modification.@Nullable Type parseModificationType(String value) {
        if (value.isEmpty()) return null;
        Modification.Type type = Modification.Type.fromName(value);
        if (type == null) LOGGER.fine("Dropping unknown modification type from wire: " + value);
        return type;
    }

    private static ReportsResponse.Report toReport(ReportEntry proto) {
        List<Object> chatMessages = new ArrayList<>();
        proto.getChatMessagesList().forEach(s -> chatMessages.add(ProtoConversions.structToMap(s)));
        return new ReportsResponse.Report(
            proto.getId(),
            proto.getType(),
            proto.getCategory(),
            proto.getReporterName(),
            proto.getReporterUuid(),
            proto.getReportedPlayerUuid(),
            proto.getReportedPlayerName(),
            proto.getSubject(),
            proto.getContent(),
            proto.getStatus(),
            proto.getPriority(),
            ProtoConversions.instantFromMillis(proto.getCreatedAt()),
            new ArrayList<>(proto.getAssignedToList()),
            chatMessages);
    }

    private static PlayerLookupResponse.PlayerData toLookupData(gg.modl.proto.modl.v1.PlayerLookupResponse.PlayerLookupData proto) {
        List<PlayerLookupResponse.RecentPunishment> recentPunishments = new ArrayList<>();
        proto.getRecentPunishmentsList().forEach(p -> recentPunishments.add(new PlayerLookupResponse.RecentPunishment(
            p.getId(), p.getType(), p.getIssuer(), p.getIssuedAt(), p.getExpiresAt(), p.getIsActive())));

        List<PlayerLookupResponse.RecentTicket> recentTickets = new ArrayList<>();
        proto.getRecentTicketsList().forEach(t -> recentTickets.add(new PlayerLookupResponse.RecentTicket(
            t.getId(), t.getTitle(), t.getCategory(), t.getStatus(), t.getCreatedAt(), t.getLastUpdated())));

        gg.modl.proto.modl.v1.PlayerLookupResponse.PlayerLookupPunishmentStats stats = proto.getPunishmentStats();
        PlayerLookupResponse.PunishmentStats punishmentStats = new PlayerLookupResponse.PunishmentStats(
            stats.getStatus(),
            stats.getTotalPunishments(),
            stats.getActivePunishments(),
            stats.getBans(),
            stats.getMutes(),
            stats.getKicks(),
            stats.getWarnings(),
            stats.getPoints());

        return new PlayerLookupResponse.PlayerData(
            proto.getMinecraftUuid(),
            proto.getCurrentUsername(),
            proto.getFirstSeen(),
            proto.getLastSeen(),
            proto.getCurrentServer(),
            proto.getIpAddress(),
            proto.getCountry(),
            proto.getProfileUrl(),
            proto.getTicketsUrl(),
            new ArrayList<>(proto.getPreviousUsernamesList()),
            punishmentStats,
            recentPunishments,
            recentTickets,
            proto.getIsOnline());
    }

    private static UUID parseAccountUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Backend sent an account with an invalid minecraftUuid: " + value, e);
        }
    }
}
