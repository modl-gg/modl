package gg.modl.minecraft.core.impl.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.request.ActionIssuer;
import gg.modl.minecraft.api.http.request.AddPunishmentEvidenceRequest;
import gg.modl.minecraft.api.http.request.AddPunishmentNoteRequest;
import gg.modl.minecraft.api.http.request.ChangePunishmentDurationRequest;
import gg.modl.minecraft.api.http.request.ChatLogBatchRequest;
import gg.modl.minecraft.api.http.request.ClaimTicketRequest;
import gg.modl.minecraft.api.http.request.CommandLogBatchRequest;
import gg.modl.minecraft.api.http.request.CreatePlayerNoteRequest;
import gg.modl.minecraft.api.http.request.CreateTicketRequest;
import gg.modl.minecraft.api.http.request.EndImportJobRequest;
import gg.modl.minecraft.api.http.request.ExportProgressRequest;
import gg.modl.minecraft.api.http.request.ModifyPunishmentTicketsRequest;
import gg.modl.minecraft.api.http.request.NotificationAcknowledgeRequest;
import gg.modl.minecraft.api.http.request.PanelAccountLinkRequest;
import gg.modl.minecraft.api.http.request.PanelLoginLinkRequest;
import gg.modl.minecraft.api.http.request.PardonPlayerRequest;
import gg.modl.minecraft.api.http.request.PardonPunishmentRequest;
import gg.modl.minecraft.api.http.request.PlayerDisconnectRequest;
import gg.modl.minecraft.api.http.request.PlayerGetRequest;
import gg.modl.minecraft.api.http.request.PlayerLoginRequest;
import gg.modl.minecraft.api.http.request.PlayerLookupRequest;
import gg.modl.minecraft.api.http.request.PlayerNameRequest;
import gg.modl.minecraft.api.http.request.PunishmentAcknowledgeRequest;
import gg.modl.minecraft.api.http.request.PunishmentCreateRequest;
import gg.modl.minecraft.api.http.request.StartImportJobRequest;
import gg.modl.minecraft.api.http.request.StatWipeAcknowledgeRequest;
import gg.modl.minecraft.api.http.request.SyncRequest;
import gg.modl.minecraft.api.http.request.TogglePunishmentOptionRequest;
import gg.modl.minecraft.api.http.response.ChatLogsResponse;
import gg.modl.minecraft.api.http.response.ClaimTicketResponse;
import gg.modl.minecraft.api.http.response.CommandLogsResponse;
import gg.modl.minecraft.api.http.response.CreateTicketResponse;
import gg.modl.minecraft.api.http.response.DashboardStatsResponse;
import gg.modl.minecraft.api.http.response.EvidenceUploadTokenResponse;
import gg.modl.minecraft.api.http.response.ImportJobResponse;
import gg.modl.minecraft.api.http.response.ImportStatusResponse;
import gg.modl.minecraft.api.http.response.LinkedAccountsResponse;
import gg.modl.minecraft.api.http.response.OnlinePlayersResponse;
import gg.modl.minecraft.api.http.response.PaginatedNotesResponse;
import gg.modl.minecraft.api.http.response.PaginatedPunishmentsResponse;
import gg.modl.minecraft.api.http.response.PanelAccountLinkResponse;
import gg.modl.minecraft.api.http.response.PanelLoginLinkResponse;
import gg.modl.minecraft.api.http.response.PardonResponse;
import gg.modl.minecraft.api.http.response.PlayerGetResponse;
import gg.modl.minecraft.api.http.response.PlayerLoginResponse;
import gg.modl.minecraft.api.http.response.PlayerLookupResponse;
import gg.modl.minecraft.api.http.response.PlayerNameResponse;
import gg.modl.minecraft.api.http.response.PlayerNoteCreateResponse;
import gg.modl.minecraft.api.http.response.PlayerProfileResponse;
import gg.modl.minecraft.api.http.response.PlayerStandingResponse;
import gg.modl.minecraft.api.http.response.PunishmentCreateResponse;
import gg.modl.minecraft.api.http.response.PunishmentDetailResponse;
import gg.modl.minecraft.api.http.response.PunishmentPreviewResponse;
import gg.modl.minecraft.api.http.response.PunishmentTypesResponse;
import gg.modl.minecraft.api.http.response.RecentPunishmentsResponse;
import gg.modl.minecraft.api.http.response.ReportsResponse;
import gg.modl.minecraft.api.http.response.RolesListResponse;
import gg.modl.minecraft.api.http.response.Staff2faTokenResponse;
import gg.modl.minecraft.api.http.response.StaffListResponse;
import gg.modl.minecraft.api.http.response.StaffPermissionsResponse;
import gg.modl.minecraft.api.http.response.StatusReportResponse;
import gg.modl.minecraft.api.http.response.SyncResponse;
import gg.modl.minecraft.api.http.response.TicketsResponse;
import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.player.OnlinePlayerPages;
import org.jspecify.annotations.Nullable;


import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

public class ModlHttpClientV2Impl extends AbstractModlHttpTransport implements ModlHttpClient {
    public static final String BASE_PATH = "/v1";
    static final String IMPORT_JOBS_PATH = "/minecraft/migration/jobs";
    private static final String SYNC_PATH = "/v2/minecraft/players/sync";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final int MAX_LOG_BODY_LENGTH = 1000;
    private static final String OPERATION_STAFF_2FA_TOKEN = "STAFF_2FA_TOKEN",
            OPERATION_PANEL_ACCOUNT_LINK = "PANEL_ACCOUNT_LINK", OPERATION_PANEL_LOGIN_LINK = "PANEL_LOGIN_LINK",
            OPERATION_COMMAND_LOGS = "COMMAND_LOGS";
    private static final Set<String> CREDENTIAL_OPERATIONS = new HashSet<>(Arrays.asList(
            OPERATION_STAFF_2FA_TOKEN, OPERATION_PANEL_ACCOUNT_LINK, OPERATION_PANEL_LOGIN_LINK, OPERATION_COMMAND_LOGS));

    private final Gson gson;

    public ModlHttpClientV2Impl(BackendConnections connections, boolean debugMode) {
        this(connections, debugMode, System::nanoTime);
    }

    ModlHttpClientV2Impl(BackendConnections connections, boolean debugMode, LongSupplier loginClock) {
        super(connections, BASE_PATH, debugMode, "V2", "modl-http-", loginClock);
        this.gson = new GsonBuilder()
                .registerTypeAdapter(Instant.class, flexibleInstantDeserializer())
                .create();
    }

    private static JsonDeserializer<Instant> flexibleInstantDeserializer() {
        return (json, typeOfT, context) -> {
            if (json == null || json.isJsonNull()) return null;
            if (!json.isJsonPrimitive()) throw new JsonParseException("Unsupported date payload: " + json);
            if (json.getAsJsonPrimitive().isNumber()) return Instant.ofEpochMilli(json.getAsLong());
            String text = json.getAsString();
            Instant parsed = WireTimestamps.parse(text);
            if (parsed == null && !WireTimestamps.isAbsent(text)) throw new JsonParseException(text.trim());
            return parsed;
        };
    }

    private static class RequestConfig {
        final String path;
        final String method;
        final Map<String, String> headers = new LinkedHashMap<>();
        final @Nullable String body;
        final @Nullable Duration timeout;

        RequestConfig(String path, String method, @Nullable String body, @Nullable Duration timeout) {
            this.path = path;
            this.method = method;
            this.body = body;
            this.timeout = timeout;
        }
    }

    private static class RequestBuilder {
        private final String path;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String method = "GET";
        private @Nullable String body;
        private @Nullable Duration timeout;

        RequestBuilder(String path) {
            this.path = path;
        }

        RequestBuilder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        RequestBuilder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        RequestBuilder GET() {
            this.method = "GET";
            this.body = null;
            return this;
        }

        RequestBuilder POST(String body) {
            this.method = "POST";
            this.body = body;
            return this;
        }

        RequestBuilder method(String method, String body) {
            this.method = method;
            this.body = body;
            return this;
        }

        RequestConfig build() {
            RequestConfig config = new RequestConfig(path, method, body, timeout);
            config.headers.putAll(headers);
            return config;
        }
    }

    private RequestBuilder requestBuilder(String endpoint) {
        return new RequestBuilder(basePath + endpoint);
    }

    @Override
    public CompletableFuture<PlayerProfileResponse> getPlayerProfile(UUID uuid) {
        return sendAsync(requestBuilder("/minecraft/players/" + uuid + "?punishmentLimit=14&noteLimit=14")
                .GET()
                .build(), PlayerProfileResponse.class);
    }

    @Override
    public CompletableFuture<PlayerStandingResponse> getPlayerStanding(UUID uuid) {
        return sendAsync(requestBuilder("/minecraft/players/" + uuid + "/standing")
                .GET()
                .build(), PlayerStandingResponse.class);
    }

    @Override
    public CompletableFuture<LinkedAccountsResponse> getLinkedAccounts(UUID uuid) {
        return sendAsync(requestBuilder("/minecraft/players/" + uuid + "/linked-accounts")
                .GET()
                .build(), LinkedAccountsResponse.class);
    }

    @Override
    public CompletableFuture<PlayerLoginResponse> playerLogin(PlayerLoginRequest request) {
        String requestBody = gson.toJson(request);
        if (debugMode) logger.info(String.format("[V2] Player login request body: %s", requestBody));

        return sendAsync(requestBuilder("/minecraft/players/login")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .timeout(LOGIN_TIMEOUT)
                .POST(requestBody)
                .build(), PlayerLoginResponse.class, "LOGIN", loginLane);
    }

    @Override
    public CompletableFuture<Void> playerDisconnect(PlayerDisconnectRequest request) {
        return sendVoid(requestBuilder("/minecraft/players/disconnect")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(request))
                .build());
    }

    @Override
    public CompletableFuture<CreateTicketResponse> createTicket(CreateTicketRequest request) {
        return sendAsync(requestBuilder("/minecraft/tickets")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(request))
                .build(), CreateTicketResponse.class);
    }

    @Override
    public CompletableFuture<CreateTicketResponse> createUnfinishedTicket(CreateTicketRequest request) {
        String requestBody = gson.toJson(request);
        if (debugMode) logger.info(String.format("[V2] Create unfinished ticket request body: %s", requestBody));

        return sendAsync(requestBuilder("/minecraft/tickets/unfinished")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(requestBody)
                .build(), CreateTicketResponse.class, "CREATE_UNFINISHED_TICKET");
    }

    @Override
    public CompletableFuture<Void> createPlayerNote(CreatePlayerNoteRequest request) {
        return sendVoid(requestBuilder("/minecraft/players/" + request.getTargetUuid() + "/notes")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(jsonWithIssuer(request, request.getIssuer()))
                .build());
    }

    @Override
    public CompletableFuture<PunishmentCreateResponse> createPunishmentWithResponse(PunishmentCreateRequest request) {
        return sendAsync(requestBuilder("/minecraft/punishments/dynamic")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(jsonWithIssuer(request, request.getIssuer()))
                .build(), PunishmentCreateResponse.class);
    }

    @Override
    public CompletableFuture<PlayerGetResponse> getPlayer(PlayerGetRequest request) {
        return sendAsync(requestBuilder("/minecraft/players?minecraftUuid=" + request.getMinecraftUuid())
                .GET()
                .build(), PlayerGetResponse.class);
    }

    @Override
    public CompletableFuture<PlayerNameResponse> getPlayer(PlayerNameRequest request) {
        return sendAsync(requestBuilder("/minecraft/players/by-name?username=" + request.getMinecraftUsername())
                .GET()
                .build(), PlayerNameResponse.class);
    }

    @Override
    public CompletableFuture<PlayerNoteCreateResponse> createPlayerNoteWithResponse(CreatePlayerNoteRequest request) {
        return sendAsync(requestBuilder("/minecraft/players/" + request.getTargetUuid() + "/notes")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(jsonWithIssuer(request, request.getIssuer()))
                .build(), PlayerNoteCreateResponse.class);
    }

    @Override
    public CompletableFuture<SyncResponse> sync(SyncRequest request) {
        String requestBody = gson.toJson(request);
        if (debugMode) logger.info(String.format("[V2] Sync request body: %s", requestBody));

        return sendAsync(new RequestBuilder(SYNC_PATH)
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .timeout(SYNC_TIMEOUT)
                .POST(requestBody)
                .build(), SyncResponse.class, "SYNC");
    }

    @Override
    public CompletableFuture<Void> acknowledgePunishment(PunishmentAcknowledgeRequest request) {
        return sendVoid(requestBuilder("/minecraft/punishments/acknowledge")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(request))
                .build());
    }

    @Override
    public CompletableFuture<Void> acknowledgeNotifications(NotificationAcknowledgeRequest request) {
        return sendVoid(requestBuilder("/minecraft/notifications/acknowledge")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(request))
                .build());
    }

    @Override
    public CompletableFuture<PunishmentTypesResponse> getPunishmentTypes() {
        return sendAsync(requestBuilder("/minecraft/punishments/types")
                .GET()
                .build(), PunishmentTypesResponse.class);
    }

    @Override
    public CompletableFuture<StaffPermissionsResponse> getStaffPermissions() {
        return sendAsync(requestBuilder("/minecraft/staff/permissions")
                .GET()
                .build(), StaffPermissionsResponse.class);
    }

    @Override
    public CompletableFuture<PlayerLookupResponse> lookupPlayer(PlayerLookupRequest request) {
        return sendAsync(requestBuilder("/minecraft/players/lookup")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(request))
                .build(), PlayerLookupResponse.class);
    }

    @Override
    public CompletableFuture<PlayerProfileResponse> lookupPlayerProfile(PlayerLookupRequest request) {
        return sendAsync(requestBuilder("/minecraft/players/lookup-profile?punishmentLimit=14&noteLimit=14")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(request))
                .build(), PlayerProfileResponse.class);
    }

    @Override
    public CompletableFuture<PardonResponse> pardonPunishment(PardonPunishmentRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("issuerName", request.getIssuerName());
        putIssuer(body, request.getIssuer());
        if (request.getReason() != null) body.put("reason", request.getReason());
        if (request.getExpectedType() != null) body.put("expectedType", request.getExpectedType());

        return sendAsync(requestBuilder("/minecraft/punishments/" + request.getPunishmentId() + "/pardon")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(body))
                .build(), PardonResponse.class);
    }

    @Override
    public CompletableFuture<PardonResponse> pardonPlayer(PardonPlayerRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("playerName", request.getPlayerName());
        body.put("issuerName", request.getIssuerName());
        putIssuer(body, request.getIssuer());
        if (request.getPunishmentType() != null) body.put("punishmentType", request.getPunishmentType());
        if (request.getReason() != null) body.put("reason", request.getReason());

        return sendAsync(requestBuilder("/minecraft/players/pardon")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(body))
                .build(), PardonResponse.class);
    }

    @Override
    public CompletableFuture<OnlinePlayersResponse> getOnlinePlayers(@Nullable String cursor) {
        return sendAsync(requestBuilder(OnlinePlayerPages.path(cursor))
                .GET()
                .build(), OnlinePlayersResponse.class);
    }

    @Override
    public CompletableFuture<RecentPunishmentsResponse> getRecentPunishments(int hours) {
        return sendAsync(requestBuilder("/minecraft/punishments/recent?hours=" + hours)
                .GET()
                .build(), RecentPunishmentsResponse.class);
    }

    @Override
    public CompletableFuture<ReportsResponse> getReports(String status) {
        String endpoint = "/minecraft/reports";
        if (status != null && !status.isEmpty()) endpoint += "?status=" + status;
        return sendAsync(requestBuilder(endpoint)
                .GET()
                .build(), ReportsResponse.class);
    }

    @Override
    public CompletableFuture<ReportsResponse> getPlayerReports(UUID playerUuid, String status) {
        String endpoint = "/minecraft/reports/player/" + playerUuid;
        if (status != null && !status.isEmpty()) endpoint += "?status=" + status;
        return sendAsync(requestBuilder(endpoint)
                .GET()
                .build(), ReportsResponse.class);
    }

    @Override
    public CompletableFuture<Void> dismissReport(String reportId, String dismissedBy, String reason,
                                                 ActionIssuer issuer) {
        Map<String, Object> body = new HashMap<>();
        if (dismissedBy != null) body.put("dismissedBy", dismissedBy);
        if (reason != null) body.put("reason", reason);
        putIssuer(body, issuer);

        return sendVoid(requestBuilder("/minecraft/reports/" + reportId + "/dismiss")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(body))
                .build());
    }

    @Override
    public CompletableFuture<Void> resolveReport(String reportId, String resolvedBy, String resolution, String punishmentId,
                                                 ActionIssuer issuer) {
        Map<String, Object> body = new HashMap<>();
        putIssuer(body, issuer);
        if (resolvedBy != null) body.put("resolvedBy", resolvedBy);
        if (resolution != null) body.put("resolution", resolution);
        if (punishmentId != null) body.put("punishmentId", punishmentId);

        return sendVoid(requestBuilder("/minecraft/reports/" + reportId + "/resolve")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(body))
                .build());
    }

    @Override
    public CompletableFuture<TicketsResponse> getTickets(@Nullable String status, @Nullable String type) {
        StringBuilder endpoint = new StringBuilder("/minecraft/tickets");
        boolean hasParam = false;

        if (status != null && !status.isEmpty() && !status.equals("all")) {
            endpoint.append("?status=").append(status);
            hasParam = true;
        }
        if (type != null && !type.isEmpty()) endpoint.append(hasParam ? "&" : "?").append("type=").append(type);

        return sendAsync(requestBuilder(endpoint.toString())
                .GET()
                .build(), TicketsResponse.class);
    }

    @Override
    public CompletableFuture<DashboardStatsResponse> getDashboardStats() {
        return sendAsync(requestBuilder("/minecraft/dashboard/stats")
                .GET()
                .build(), DashboardStatsResponse.class);
    }

    @Override
    public CompletableFuture<PunishmentPreviewResponse> getPunishmentPreview(UUID playerUuid, int typeOrdinal) {
        String endpoint = "/minecraft/punishments/preview?playerUuid=" + playerUuid + "&typeOrdinal=" + typeOrdinal;
        return sendAsync(requestBuilder(endpoint)
                .GET()
                .build(), PunishmentPreviewResponse.class);
    }

    @Override
    public CompletableFuture<Void> addPunishmentNote(AddPunishmentNoteRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("issuerName", request.getIssuerName());
        putIssuer(body, request.getIssuer());
        body.put("note", request.getNote());

        return sendVoid(requestBuilder("/minecraft/punishments/" + request.getPunishmentId() + "/note")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(body))
                .build());
    }

    @Override
    public CompletableFuture<Void> addPunishmentEvidence(AddPunishmentEvidenceRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("issuerName", request.getIssuerName());
        putIssuer(body, request.getIssuer());
        body.put("evidenceUrl", request.getEvidenceUrl());

        return sendVoid(requestBuilder("/minecraft/punishments/" + request.getPunishmentId() + "/evidence")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(body))
                .build());
    }

    @Override
    public CompletableFuture<Void> changePunishmentDuration(ChangePunishmentDurationRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("issuerName", request.getIssuerName());
        putIssuer(body, request.getIssuer());
        body.put("newDuration", request.getNewDuration());

        return sendVoid(requestBuilder("/minecraft/punishments/" + request.getPunishmentId() + "/duration")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(body))
                .build());
    }

    @Override
    public CompletableFuture<Void> togglePunishmentOption(TogglePunishmentOptionRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("issuerName", request.getIssuerName());
        putIssuer(body, request.getIssuer());
        body.put("option", request.getOption());
        body.put("enabled", request.isEnabled());

        return sendVoid(requestBuilder("/minecraft/punishments/" + request.getPunishmentId() + "/toggle")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(body))
                .build());
    }

    @Override
    public CompletableFuture<ClaimTicketResponse> claimTicket(ClaimTicketRequest request) {
        Map<String, String> body = new HashMap<>();
        body.put("playerUuid", request.getPlayerUuid());
        body.put("playerName", request.getPlayerName());
        if (request.getAccessKey() != null) body.put("accessKey", request.getAccessKey());

        return sendAsync(requestBuilder("/minecraft/tickets/" + request.getTicketId() + "/claim")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(body))
                .build(), ClaimTicketResponse.class);
    }

    @Override
    public CompletableFuture<StaffListResponse> getStaffList() {
        return sendAsync(requestBuilder("/minecraft/staff")
                .GET()
                .build(), StaffListResponse.class);
    }

    @Override
    public CompletableFuture<Void> reportStaffDisconnect(String minecraftUuid, long sessionDurationMs) {
        Map<String, Object> body = new HashMap<>();
        body.put("minecraftUuid", minecraftUuid);
        body.put("sessionDurationMs", sessionDurationMs);

        return sendVoid(requestBuilder("/minecraft/staff/disconnect")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(body))
                .build());
    }

    @Override
    public CompletableFuture<Void> updateStaffRole(String staffId, String roleName, @Nullable String actingStaffId) {
        Map<String, String> body = new HashMap<>();
        body.put("role", roleName);

        RequestBuilder builder = requestBuilder("/minecraft/staff/" + staffId + "/role")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON);
        if (actingStaffId != null && !actingStaffId.trim().isEmpty()) builder.header(HEADER_ACTING_STAFF_ID, actingStaffId);

        return sendVoid(builder
                .method("PATCH", gson.toJson(body))
                .build());
    }

    @Override
    public CompletableFuture<RolesListResponse> getRoles() {
        return sendAsync(requestBuilder("/minecraft/roles")
                .GET()
                .build(), RolesListResponse.class);
    }

    @Override
    public CompletableFuture<Void> updateRolePermissions(String roleId, List<String> permissions,
                                                       @Nullable String actingStaffId) {
        Map<String, Object> body = new HashMap<>();
        body.put("permissions", permissions);

        RequestBuilder builder = requestBuilder("/minecraft/roles/" + roleId + "/permissions")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON);
        if (actingStaffId != null && !actingStaffId.trim().isEmpty()) builder.header(HEADER_ACTING_STAFF_ID, actingStaffId);

        return sendVoid(builder
                .method("PATCH", gson.toJson(body))
                .build());
    }

    @Override
    public CompletableFuture<PunishmentDetailResponse> getPunishmentDetail(String punishmentId) {
        return sendAsync(requestBuilder("/minecraft/punishments/" + punishmentId)
                .GET()
                .build(), PunishmentDetailResponse.class);
    }

    @Override
    public CompletableFuture<EvidenceUploadTokenResponse> createEvidenceUploadToken(String punishmentId, String issuerName,
                                                                                    ActionIssuer issuer) {
        Map<String, Object> body = new HashMap<>();
        body.put("issuerName", issuerName);
        putIssuer(body, issuer);

        return sendAsync(requestBuilder("/minecraft/punishments/" + punishmentId + "/upload-token")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(body))
                .build(), EvidenceUploadTokenResponse.class);
    }

    @Override
    public CompletableFuture<Void> updatePlayerServer(String minecraftUuid, String serverName,
                                                      @Nullable String serverInstanceId) {
        Map<String, String> body = new HashMap<>();
        body.put("minecraftUuid", minecraftUuid);
        body.put("serverName", serverName);
        if (serverInstanceId != null) body.put("serverInstanceId", serverInstanceId);
        return sendVoid(requestBuilder("/minecraft/players/update-server")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(body))
                .build());
    }

    @Override
    public CompletableFuture<Void> modifyPunishmentTickets(ModifyPunishmentTicketsRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("issuerName", request.getIssuerName());
        putIssuer(body, request.getIssuer());
        body.put("addTicketIds", request.getAddTicketIds());
        body.put("removeTicketIds", request.getRemoveTicketIds());
        body.put("modifyAssociatedTickets", request.isModifyAssociatedTickets());

        return sendVoid(requestBuilder("/minecraft/punishments/" + request.getPunishmentId() + "/tickets")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(body))
                .build());
    }

    @Override
    public CompletableFuture<Void> acknowledgeStatWipe(StatWipeAcknowledgeRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("punishmentId", request.getPunishmentId());
        body.put("serverName", request.getServerName());
        body.put("success", request.isSuccess());

        return sendVoid(requestBuilder("/minecraft/punishments/" + request.getPunishmentId() + "/stat-wipe-acknowledge")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(body))
                .build());
    }

    @Override
    public CompletableFuture<TicketsResponse> getTicketsByIds(List<String> ticketIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", ticketIds);

        return sendAsync(requestBuilder("/minecraft/tickets/by-ids")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(body))
                .build(), TicketsResponse.class);
    }

    @Override
    public CompletableFuture<ImportJobResponse> startImport(StartImportJobRequest request) {
        return sendAsync(requestBuilder(IMPORT_JOBS_PATH)
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(request))
                .build(), ImportJobResponse.class);
    }

    @Override
    public CompletableFuture<StatusReportResponse> getStatusReport() {
        return sendAsync(requestBuilder("/minecraft/status")
                .GET()
                .build(), StatusReportResponse.class);
    }

    @Override
    public CompletableFuture<ImportStatusResponse> getImportStatus() {
        return sendAsync(requestBuilder(IMPORT_JOBS_PATH + "/latest")
                .GET()
                .build(), ImportStatusResponse.class);
    }

    @Override
    public CompletableFuture<ImportJobResponse> getImportJob(String jobId) {
        return sendAsync(requestBuilder(IMPORT_JOBS_PATH + "/" + jobId)
                .GET()
                .build(), ImportJobResponse.class);
    }

    @Override
    public CompletableFuture<ImportJobResponse> reportExportProgress(String jobId,
                                                                     ExportProgressRequest request) {
        return sendAsync(requestBuilder(IMPORT_JOBS_PATH + "/" + jobId + "/progress")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(request))
                .build(), ImportJobResponse.class);
    }

    @Override
    public CompletableFuture<ImportJobResponse> endImport(String jobId, EndImportJobRequest request) {
        return sendAsync(requestBuilder(IMPORT_JOBS_PATH + "/" + jobId + "/end")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(request))
                .build(), ImportJobResponse.class);
    }

    @Override
    public CompletableFuture<Staff2faTokenResponse> generateStaff2faToken(String minecraftUuid, @Nullable String ip) {
        Map<String, String> body = new HashMap<>();
        body.put("minecraftUuid", minecraftUuid);
        if (ip != null) body.put("ip", ip);
        return sendAsync(requestBuilder("/minecraft/staff/2fa/generate")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(body))
                .build(), Staff2faTokenResponse.class, OPERATION_STAFF_2FA_TOKEN);
    }

    @Override
    public CompletableFuture<PanelAccountLinkResponse> linkPanelAccount(PanelAccountLinkRequest request) {
        return sendAsync(requestBuilder("/minecraft/panel/link-account")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(request))
                .build(), PanelAccountLinkResponse.class, OPERATION_PANEL_ACCOUNT_LINK);
    }

    @Override
    public CompletableFuture<PanelLoginLinkResponse> createPanelLoginLink(PanelLoginLinkRequest request) {
        return sendAsync(requestBuilder("/minecraft/panel/login-link")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(request))
                .build(), PanelLoginLinkResponse.class, OPERATION_PANEL_LOGIN_LINK);
    }

    @Override
    public CompletableFuture<Void> submitChatLogs(ChatLogBatchRequest chatLogBatch) {
        return sendVoid(requestBuilder("/minecraft/players/chat-log")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(chatLogBatch))
                .build());
    }

    @Override
    public CompletableFuture<Void> submitCommandLogs(CommandLogBatchRequest commandLogBatch) {
        return sendVoid(requestBuilder("/minecraft/players/command-log")
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(gson.toJson(commandLogBatch))
                .build());
    }

    @Override
    public CompletableFuture<ChatLogsResponse> getChatLogs(String playerUuid, int limit) {
        return sendAsync(requestBuilder("/minecraft/players/" + playerUuid + "/chat-logs?limit=" + limit)
                .GET()
                .build(), ChatLogsResponse.class);
    }

    @Override
    public CompletableFuture<CommandLogsResponse> getCommandLogs(String playerUuid, int limit) {
        return sendAsync(requestBuilder("/minecraft/players/" + playerUuid + "/command-logs?limit=" + limit)
                .GET()
                .build(), CommandLogsResponse.class, OPERATION_COMMAND_LOGS);
    }

    @Override
    public CompletableFuture<PaginatedPunishmentsResponse> getPlayerPunishments(UUID uuid, int page, int limit) {
        return sendAsync(requestBuilder("/minecraft/players/" + uuid + "/punishments?page=" + page + "&limit=" + limit)
                .GET()
                .build(), PaginatedPunishmentsResponse.class);
    }

    @Override
    public CompletableFuture<PaginatedNotesResponse> getPlayerNotes(UUID uuid, int page, int limit) {
        return sendAsync(requestBuilder("/minecraft/players/" + uuid + "/notes?page=" + page + "&limit=" + limit)
                .GET()
                .build(), PaginatedNotesResponse.class);
    }

    @Override
    public CompletableFuture<LinkedAccountsResponse> getLinkedAccounts(UUID uuid, int page, int limit) {
        return sendAsync(requestBuilder("/minecraft/players/" + uuid + "/linked-accounts?page=" + page + "&limit=" + limit)
                .GET()
                .build(), LinkedAccountsResponse.class);
    }

    private CompletableFuture<Void> sendVoid(RequestConfig request) {
        return execute(toHttpRequest(request), null, backgroundLane, (requestId, responseBody) -> responseBody)
                .thenAccept(ignored -> { });
    }

    private <T> CompletableFuture<T> sendAsync(RequestConfig request, Class<T> responseType) {
        return sendAsync(request, responseType, null);
    }

    private <T> CompletableFuture<T> sendAsync(RequestConfig request, Class<T> responseType, @Nullable String operation) {
        return sendAsync(request, responseType, operation, backgroundLane);
    }

    private <T> CompletableFuture<T> sendAsync(RequestConfig request, Class<T> responseType,
                                               @Nullable String operation, RequestLane lane) {
        return execute(toHttpRequest(request), operation, lane,
                (requestId, responseBody) -> decodeJson(requestId, responseBody, responseType));
    }

    private HttpRequest toHttpRequest(RequestConfig request) {
        byte[] body = request.body == null ? null : request.body.getBytes(StandardCharsets.UTF_8);
        return newRequest(request.path, request.method, body, request.timeout, request.headers);
    }

    private <T> T decodeJson(String requestId, byte[] responseBody, Class<T> responseType) {
        String text = new String(responseBody, StandardCharsets.UTF_8);
        try {
            T result = gson.fromJson(text, responseType);
            if (debugMode) logger.info(String.format("[V2-REQ-%s] Successfully parsed response to %s", requestId, responseType.getSimpleName()));
            return result;
        } catch (Exception e) {
            logger.severe(String.format("[V2-REQ-%s] Failed to parse response: %s", requestId, e.getMessage()));
            throw new RuntimeException("Failed to parse V2 response: " + e.getMessage(), e);
        }
    }

    @Override
    protected void logRequest(String requestId, HttpRequest request) {
        logger.info(String.format("[V2-REQ-%s] %s %s", requestId, request.method, request.url));
        logger.info(String.format("[V2-REQ-%s] Headers: %s", requestId, request.headers));
        if (request.body != null) logger.info(String.format("[V2-REQ-%s] Body present", requestId));
    }

    @Override
    protected void logResponse(String requestId, int statusCode, byte[] body, long durationMs,
                               @Nullable String operation) {
        logger.info(String.format("[V2-RES-%s] Status: %d (took %dms)", requestId, statusCode, durationMs));
        if (body.length == 0 || CREDENTIAL_OPERATIONS.contains(operation)) return;
        String responseBody = new String(body, StandardCharsets.UTF_8);
        if ("LOGIN".equals(operation) || "SYNC".equals(operation) || responseBody.length() <= MAX_LOG_BODY_LENGTH) {
            logger.info(String.format("[V2-RES-%s] Body: %s", requestId, responseBody));
        } else {
            logger.info(String.format("[V2-RES-%s] Body: %s... (truncated, %d chars total)",
                    requestId, responseBody.substring(0, MAX_LOG_BODY_LENGTH), responseBody.length()));
        }
    }

    @Override
    protected FailureBody readFailure(int statusCode, byte[] body) {
        String responseBody = new String(body, StandardCharsets.UTF_8);
        return new FailureBody(extractErrorMessage(responseBody, statusCode), extractErrorCode(responseBody));
    }

    private @Nullable String extractErrorCode(String responseBody) {
        try {
            JsonObject errorResponse = gson.fromJson(responseBody, JsonObject.class);
            if (errorResponse != null && errorResponse.has("code") && errorResponse.get("code").isJsonPrimitive()) {
                return errorResponse.get("code").getAsString();
            }
        } catch (Exception unreadable) {
            return null;
        }
        return null;
    }

    private String extractErrorMessage(String responseBody, int statusCode) {
        try {
            JsonObject errorResponse = gson.fromJson(responseBody, JsonObject.class);
            if (errorResponse != null) {
                String error = errorResponse.has("error") ? errorResponse.get("error").getAsString() : "";
                if (error.isEmpty() && errorResponse.has("message")) error = errorResponse.get("message").getAsString();
                String details = errorResponse.has("errors") ? errorResponse.get("errors").getAsString() : "";
                String message = !details.isEmpty() ? error + " Details: " + details : error;
                return message.isEmpty()
                        ? String.format("V2 request failed with status code %d: %s", statusCode, responseBody)
                        : message;
            }
        } catch (Exception ignored) {
        }
        return String.format("V2 request failed with status code %d: %s", statusCode, responseBody);
    }

    private String jsonWithIssuer(Object request, ActionIssuer issuer) {
        JsonObject body = gson.toJsonTree(request).getAsJsonObject();
        issuer.writeTo(staffId -> body.addProperty("issuerId", staffId), console -> body.addProperty("console", console));
        return gson.toJson(body);
    }

    private static void putIssuer(Map<String, Object> body, ActionIssuer issuer) {
        issuer.writeTo(staffId -> body.put("issuerId", staffId), console -> body.put("console", console));
    }
}
