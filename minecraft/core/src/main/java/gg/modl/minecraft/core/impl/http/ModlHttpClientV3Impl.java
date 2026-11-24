package gg.modl.minecraft.core.impl.http;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
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
import gg.modl.minecraft.core.impl.http.proto.PlayerProtoMapper;
import gg.modl.minecraft.core.impl.http.proto.PunishmentProtoMapper;
import gg.modl.minecraft.core.impl.http.proto.StaffRoleProtoMapper;
import gg.modl.minecraft.core.impl.http.proto.SyncProtoMapper;
import gg.modl.minecraft.core.impl.http.proto.TicketProtoMapper;
import gg.modl.minecraft.core.player.OnlinePlayerPages;
import gg.modl.proto.modl.v1.ApiError;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class ModlHttpClientV3Impl extends AbstractModlHttpTransport implements ModlHttpClient {
    public static final String BASE_PATH = "/v3";
    private static final String HEADER_ACCEPT = "Accept", CONTENT_TYPE_PROTOBUF = "application/x-protobuf";

    private final ModlHttpClientV2Impl legacyClient;

    public ModlHttpClientV3Impl(BackendConnections connections, boolean debugMode) {
        super(connections, BASE_PATH, debugMode, "V3", "modl-http-v3-", System::nanoTime);
        this.legacyClient = new ModlHttpClientV2Impl(connections, debugMode);
    }

    @Override
    public void shutdown() {
        legacyClient.shutdown();
        super.shutdown();
    }

    @Override
    public CompletableFuture<PlayerLoginResponse> playerLogin(PlayerLoginRequest request) {
        return post("/minecraft/players/login", PlayerProtoMapper.toProto(request).toByteArray(), LOGIN_TIMEOUT,
            gg.modl.proto.modl.v1.PlayerLoginResponse.parser(), PlayerProtoMapper::toLoginResponse, "LOGIN",
            loginLane);
    }

    @Override
    public CompletableFuture<Void> playerDisconnect(PlayerDisconnectRequest request) {
        return postVoid("/minecraft/players/disconnect", PlayerProtoMapper.toProto(request).toByteArray());
    }

    @Override
    public CompletableFuture<Void> updatePlayerServer(String minecraftUuid, String serverName,
                                                      @Nullable String serverInstanceId) {
        byte[] body = PlayerProtoMapper.toUpdateServerRequest(minecraftUuid, serverName, serverInstanceId).toByteArray();
        return postVoid("/minecraft/players/update-server", body);
    }

    @Override
    public CompletableFuture<OnlinePlayersResponse> getOnlinePlayers(@Nullable String cursor) {
        return get(OnlinePlayerPages.path(cursor),
            gg.modl.proto.modl.v1.OnlinePlayersResponse.parser(), PlayerProtoMapper::toOnlinePlayersResponse);
    }

    @Override
    public CompletableFuture<PlayerProfileResponse> getPlayerProfile(UUID uuid) {
        return get("/minecraft/players/" + uuid + "?punishmentLimit=14&noteLimit=14",
            gg.modl.proto.modl.v1.PlayerProfileResponse.parser(), PlayerProtoMapper::toPlayerProfileResponse);
    }

    @Override
    public CompletableFuture<PlayerStandingResponse> getPlayerStanding(UUID uuid) {
        return legacyClient.getPlayerStanding(uuid);
    }

    @Override
    public CompletableFuture<PlayerGetResponse> getPlayer(PlayerGetRequest request) {
        return get("/minecraft/players?minecraftUuid=" + request.getMinecraftUuid() + "&queryMojang=true",
            gg.modl.proto.modl.v1.PlayerGetResponse.parser(), PlayerProtoMapper::toPlayerGetResponse);
    }

    @Override
    public CompletableFuture<PlayerNameResponse> getPlayer(PlayerNameRequest request) {
        return get("/minecraft/players/by-name?username=" + request.getMinecraftUsername() + "&queryMojang=true",
            gg.modl.proto.modl.v1.PlayerNameResponse.parser(), PlayerProtoMapper::toPlayerNameResponse);
    }

    @Override
    public CompletableFuture<PlayerLookupResponse> lookupPlayer(PlayerLookupRequest request) {
        return post("/minecraft/players/lookup", PlayerProtoMapper.toProto(request).toByteArray(),
            gg.modl.proto.modl.v1.PlayerLookupResponse.parser(), PlayerProtoMapper::toPlayerLookupResponse);
    }

    @Override
    public CompletableFuture<PlayerProfileResponse> lookupPlayerProfile(PlayerLookupRequest request) {
        return post("/minecraft/players/lookup-profile", PlayerProtoMapper.toProto(request).toByteArray(),
            gg.modl.proto.modl.v1.PlayerProfileResponse.parser(), PlayerProtoMapper::toPlayerProfileResponse);
    }

    @Override
    public CompletableFuture<Void> createPlayerNote(CreatePlayerNoteRequest request) {
        return postVoid("/minecraft/players/" + request.getTargetUuid() + "/notes",
            PlayerProtoMapper.toProto(request).toByteArray());
    }

    @Override
    public CompletableFuture<PlayerNoteCreateResponse> createPlayerNoteWithResponse(CreatePlayerNoteRequest request) {
        return post("/minecraft/players/" + request.getTargetUuid() + "/notes",
            PlayerProtoMapper.toProto(request).toByteArray(),
            gg.modl.proto.modl.v1.PlayerNoteCreateResponse.parser(), PlayerProtoMapper::toPlayerNoteCreateResponse);
    }

    @Override
    public CompletableFuture<LinkedAccountsResponse> getLinkedAccounts(UUID uuid) {
        return get("/minecraft/players/" + uuid + "/linked-accounts",
            gg.modl.proto.modl.v1.LinkedAccountsResponse.parser(), PlayerProtoMapper::toLinkedAccountsResponse);
    }

    @Override
    public CompletableFuture<LinkedAccountsResponse> getLinkedAccounts(UUID uuid, int page, int limit) {
        return get("/minecraft/players/" + uuid + "/linked-accounts?page=" + page + "&limit=" + limit,
            gg.modl.proto.modl.v1.LinkedAccountsResponse.parser(), PlayerProtoMapper::toLinkedAccountsResponse);
    }

    @Override
    public CompletableFuture<PaginatedPunishmentsResponse> getPlayerPunishments(UUID uuid, int page, int limit) {
        return get("/minecraft/players/" + uuid + "/punishments?page=" + page + "&limit=" + limit,
            gg.modl.proto.modl.v1.PaginatedPunishmentsResponse.parser(), PlayerProtoMapper::toPaginatedPunishmentsResponse);
    }

    @Override
    public CompletableFuture<PaginatedNotesResponse> getPlayerNotes(UUID uuid, int page, int limit) {
        return get("/minecraft/players/" + uuid + "/notes?page=" + page + "&limit=" + limit,
            gg.modl.proto.modl.v1.PaginatedNotesResponse.parser(), PlayerProtoMapper::toPaginatedNotesResponse);
    }

    @Override
    public CompletableFuture<PardonResponse> pardonPlayer(PardonPlayerRequest request) {
        return post("/minecraft/players/pardon", PlayerProtoMapper.toProto(request).toByteArray(),
            gg.modl.proto.modl.v1.PardonResponse.parser(), PlayerProtoMapper::toPardonResponse);
    }

    @Override
    public CompletableFuture<Void> acknowledgeNotifications(NotificationAcknowledgeRequest request) {
        return postVoid("/minecraft/notifications/acknowledge", PlayerProtoMapper.toProto(request).toByteArray());
    }

    @Override
    public CompletableFuture<Void> submitChatLogs(ChatLogBatchRequest request) {
        return postVoid("/minecraft/players/chat-log", PlayerProtoMapper.toProto(request).toByteArray());
    }

    @Override
    public CompletableFuture<Void> submitCommandLogs(CommandLogBatchRequest request) {
        return postVoid("/minecraft/players/command-log", PlayerProtoMapper.toProto(request).toByteArray());
    }

    @Override
    public CompletableFuture<PunishmentCreateResponse> createPunishmentWithResponse(PunishmentCreateRequest request) {
        return post("/minecraft/punishments/dynamic", PunishmentProtoMapper.toProto(request).toByteArray(),
            gg.modl.proto.modl.v1.PunishmentCreateResponse.parser(), PunishmentProtoMapper::toPunishmentCreateResponse);
    }

    @Override
    public CompletableFuture<Void> acknowledgePunishment(PunishmentAcknowledgeRequest request) {
        return postVoid("/minecraft/punishments/acknowledge", PlayerProtoMapper.toProto(request).toByteArray());
    }

    @Override
    public CompletableFuture<Void> acknowledgeStatWipe(StatWipeAcknowledgeRequest request) {
        return postVoid("/minecraft/punishments/" + request.getPunishmentId() + "/stat-wipe-acknowledge",
            PunishmentProtoMapper.toProto(request).toByteArray());
    }

    @Override
    public CompletableFuture<Void> changePunishmentDuration(ChangePunishmentDurationRequest request) {
        return postVoid("/minecraft/punishments/" + request.getPunishmentId() + "/duration",
            PunishmentProtoMapper.toProto(request).toByteArray());
    }

    @Override
    public CompletableFuture<Void> togglePunishmentOption(TogglePunishmentOptionRequest request) {
        return postVoid("/minecraft/punishments/" + request.getPunishmentId() + "/toggle",
            PunishmentProtoMapper.toProto(request).toByteArray());
    }

    @Override
    public CompletableFuture<Void> addPunishmentNote(AddPunishmentNoteRequest request) {
        return postVoid("/minecraft/punishments/" + request.getPunishmentId() + "/note",
            PunishmentProtoMapper.toProto(request).toByteArray());
    }

    @Override
    public CompletableFuture<Void> addPunishmentEvidence(AddPunishmentEvidenceRequest request) {
        return postVoid("/minecraft/punishments/" + request.getPunishmentId() + "/evidence",
            PunishmentProtoMapper.toProto(request).toByteArray());
    }

    @Override
    public CompletableFuture<EvidenceUploadTokenResponse> createEvidenceUploadToken(String punishmentId,
                                                                                    String issuerName,
                                                                                    ActionIssuer issuer) {
        return post("/minecraft/punishments/" + punishmentId + "/upload-token",
            PunishmentProtoMapper.toUploadTokenRequest(issuerName, issuer).toByteArray(),
            gg.modl.proto.modl.v1.EvidenceUploadTokenResponse.parser(), PunishmentProtoMapper::toUploadTokenResponse);
    }

    @Override
    public CompletableFuture<PardonResponse> pardonPunishment(PardonPunishmentRequest request) {
        return post("/minecraft/punishments/" + request.getPunishmentId() + "/pardon",
            PunishmentProtoMapper.toProto(request).toByteArray(),
            gg.modl.proto.modl.v1.PardonResponse.parser(), PlayerProtoMapper::toPardonResponse);
    }

    @Override
    public CompletableFuture<Void> modifyPunishmentTickets(ModifyPunishmentTicketsRequest request) {
        return postVoid("/minecraft/punishments/" + request.getPunishmentId() + "/tickets",
            PunishmentProtoMapper.toProto(request).toByteArray());
    }

    @Override
    public CompletableFuture<PunishmentPreviewResponse> getPunishmentPreview(UUID playerUuid, int typeOrdinal) {
        return get("/minecraft/punishments/preview?playerUuid=" + playerUuid + "&typeOrdinal=" + typeOrdinal,
            gg.modl.proto.modl.v1.PunishmentPreviewResponse.parser(), PunishmentProtoMapper::toPreviewResponse);
    }

    @Override
    public CompletableFuture<RecentPunishmentsResponse> getRecentPunishments(int hours) {
        return get("/minecraft/punishments/recent?hours=" + hours,
            gg.modl.proto.modl.v1.RecentPunishmentsResponse.parser(), PunishmentProtoMapper::toRecentResponse);
    }

    @Override
    public CompletableFuture<PunishmentDetailResponse> getPunishmentDetail(String punishmentId) {
        return get("/minecraft/punishments/" + punishmentId,
            gg.modl.proto.modl.v1.PunishmentDetailResponse.parser(), PunishmentProtoMapper::toDetailResponse);
    }

    @Override
    public CompletableFuture<PunishmentTypesResponse> getPunishmentTypes() {
        return get("/minecraft/punishments/types",
            gg.modl.proto.modl.v1.PunishmentTypesResponse.parser(), StaffRoleProtoMapper::toPunishmentTypesResponse);
    }

    @Override
    public CompletableFuture<SyncResponse> sync(SyncRequest request) {
        return post("/minecraft/players/sync", SyncProtoMapper.toProto(request).toByteArray(), SYNC_TIMEOUT,
            gg.modl.proto.modl.v1.SyncResponse.parser(), SyncProtoMapper::toSyncResponse, "SYNC");
    }

    @Override
    public CompletableFuture<CreateTicketResponse> createTicket(CreateTicketRequest request) {
        return post("/minecraft/tickets", TicketProtoMapper.toProto(request).toByteArray(),
            gg.modl.proto.modl.v1.MinecraftCreateTicketResponse.parser(), TicketProtoMapper::toCreateTicketResponse);
    }

    @Override
    public CompletableFuture<CreateTicketResponse> createUnfinishedTicket(CreateTicketRequest request) {
        return post("/minecraft/tickets/unfinished", TicketProtoMapper.toProto(request).toByteArray(), null,
            gg.modl.proto.modl.v1.MinecraftCreateTicketResponse.parser(), TicketProtoMapper::toCreateTicketResponse,
            "CREATE_UNFINISHED_TICKET");
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
        return get(endpoint.toString(),
            gg.modl.proto.modl.v1.TicketsResponse.parser(), TicketProtoMapper::toTicketsResponse);
    }

    @Override
    public CompletableFuture<ClaimTicketResponse> claimTicket(ClaimTicketRequest request) {
        return post("/minecraft/tickets/" + request.getTicketId() + "/claim",
            TicketProtoMapper.toProto(request).toByteArray(),
            gg.modl.proto.modl.v1.ClaimTicketResponse.parser(), TicketProtoMapper::toClaimTicketResponse);
    }

    @Override
    public CompletableFuture<TicketsResponse> getTicketsByIds(List<String> ticketIds) {
        return post("/minecraft/tickets/by-ids", TicketProtoMapper.toTicketsByIdsRequest(ticketIds).toByteArray(),
            gg.modl.proto.modl.v1.TicketsResponse.parser(), TicketProtoMapper::toTicketsResponse);
    }

    @Override
    public CompletableFuture<ReportsResponse> getReports(String status) {
        String endpoint = "/minecraft/reports";
        if (status != null && !status.isEmpty()) endpoint += "?status=" + status;
        return get(endpoint, gg.modl.proto.modl.v1.ReportsResponse.parser(), PlayerProtoMapper::toReportsResponse);
    }

    @Override
    public CompletableFuture<ReportsResponse> getPlayerReports(UUID playerUuid, String status) {
        String endpoint = "/minecraft/reports/player/" + playerUuid;
        if (status != null && !status.isEmpty()) endpoint += "?status=" + status;
        return get(endpoint, gg.modl.proto.modl.v1.ReportsResponse.parser(), PlayerProtoMapper::toReportsResponse);
    }

    @Override
    public CompletableFuture<Void> dismissReport(String reportId, String dismissedBy, String reason,
                                                 ActionIssuer issuer) {
        return postVoid("/minecraft/reports/" + reportId + "/dismiss",
            TicketProtoMapper.toDismissReportRequest(dismissedBy, reason, issuer).toByteArray());
    }

    @Override
    public CompletableFuture<Void> resolveReport(String reportId, String resolvedBy, String resolution,
                                                String punishmentId, ActionIssuer issuer) {
        return postVoid("/minecraft/reports/" + reportId + "/resolve",
            TicketProtoMapper.toResolveReportRequest(resolvedBy, resolution, punishmentId, issuer).toByteArray());
    }

    @Override
    public CompletableFuture<StaffListResponse> getStaffList() {
        return get("/minecraft/staff",
            gg.modl.proto.modl.v1.StaffListResponse.parser(), StaffRoleProtoMapper::toStaffListResponse);
    }

    @Override
    public CompletableFuture<StaffPermissionsResponse> getStaffPermissions() {
        return get("/minecraft/staff/permissions",
            gg.modl.proto.modl.v1.StaffPermissionsListResponse.parser(), StaffRoleProtoMapper::toStaffPermissionsResponse);
    }

    @Override
    public CompletableFuture<Void> updateStaffRole(String staffId, String roleName, @Nullable String actingStaffId) {
        return patchVoid("/minecraft/staff/" + staffId + "/role",
            StaffRoleProtoMapper.toUpdateStaffRoleRequest(roleName).toByteArray(), actingStaffId);
    }

    @Override
    public CompletableFuture<Void> reportStaffDisconnect(String minecraftUuid, long sessionDurationMs) {
        return postVoid("/minecraft/staff/disconnect",
            StaffRoleProtoMapper.toStaffDisconnectRequest(minecraftUuid, sessionDurationMs).toByteArray());
    }

    @Override
    public CompletableFuture<RolesListResponse> getRoles() {
        return get("/minecraft/roles",
            gg.modl.proto.modl.v1.MinecraftRoleListResponse.parser(), StaffRoleProtoMapper::toRolesListResponse);
    }

    @Override
    public CompletableFuture<Void> updateRolePermissions(String roleId, List<String> permissions,
                                                       @Nullable String actingStaffId) {
        return patchVoid("/minecraft/roles/" + roleId + "/permissions",
            StaffRoleProtoMapper.toUpdateRolePermissionsRequest(permissions).toByteArray(), actingStaffId);
    }

    @Override
    public CompletableFuture<DashboardStatsResponse> getDashboardStats() {
        return get("/minecraft/dashboard/stats",
            gg.modl.proto.modl.v1.MinecraftDashboardResponse.parser(), StaffRoleProtoMapper::toDashboardStatsResponse);
    }

    @Override
    public CompletableFuture<ImportJobResponse> startImport(StartImportJobRequest request) {
        return legacyClient.startImport(request);
    }

    @Override
    public CompletableFuture<ImportStatusResponse> getImportStatus() {
        return legacyClient.getImportStatus();
    }

    @Override
    public CompletableFuture<StatusReportResponse> getStatusReport() {
        return legacyClient.getStatusReport();
    }

    @Override
    public CompletableFuture<ImportJobResponse> getImportJob(String jobId) {
        return legacyClient.getImportJob(jobId);
    }

    @Override
    public CompletableFuture<ImportJobResponse> reportExportProgress(String jobId,
                                                                     ExportProgressRequest request) {
        return legacyClient.reportExportProgress(jobId, request);
    }

    @Override
    public CompletableFuture<ImportJobResponse> endImport(String jobId, EndImportJobRequest request) {
        return legacyClient.endImport(jobId, request);
    }

    @Override
    public CompletableFuture<Staff2faTokenResponse> generateStaff2faToken(String minecraftUuid, @Nullable String ip) {
        return legacyClient.generateStaff2faToken(minecraftUuid, ip);
    }

    @Override
    public CompletableFuture<PanelAccountLinkResponse> linkPanelAccount(PanelAccountLinkRequest request) {
        return legacyClient.linkPanelAccount(request);
    }

    @Override
    public CompletableFuture<PanelLoginLinkResponse> createPanelLoginLink(PanelLoginLinkRequest request) {
        return legacyClient.createPanelLoginLink(request);
    }

    @Override
    public CompletableFuture<ChatLogsResponse> getChatLogs(String playerUuid, int limit) {
        return legacyClient.getChatLogs(playerUuid, limit);
    }

    @Override
    public CompletableFuture<CommandLogsResponse> getCommandLogs(String playerUuid, int limit) {
        return legacyClient.getCommandLogs(playerUuid, limit);
    }

    private <P extends Message, R> CompletableFuture<R> get(String endpoint, Parser<P> parser, Function<P, R> mapper) {
        return send(request(endpoint, "GET", null, null, null), parser, mapper, null, backgroundLane);
    }

    private <P extends Message, R> CompletableFuture<R> post(String endpoint, byte[] body,
                                                             Parser<P> parser, Function<P, R> mapper) {
        return post(endpoint, body, null, parser, mapper, null);
    }

    private <P extends Message, R> CompletableFuture<R> post(String endpoint, byte[] body, @Nullable Duration timeout,
                                                             Parser<P> parser, Function<P, R> mapper,
                                                             @Nullable String operation) {
        return send(request(endpoint, "POST", body, timeout, null), parser, mapper, operation, backgroundLane);
    }

    private <P extends Message, R> CompletableFuture<R> post(String endpoint, byte[] body, @Nullable Duration timeout,
                                                             Parser<P> parser, Function<P, R> mapper,
                                                             @Nullable String operation, RequestLane lane) {
        return send(request(endpoint, "POST", body, timeout, null), parser, mapper, operation, lane);
    }

    private CompletableFuture<Void> postVoid(String endpoint, byte[] body) {
        return sendVoid(request(endpoint, "POST", body, null, null));
    }

    private CompletableFuture<Void> patchVoid(String endpoint, byte[] body, @Nullable String actingStaffId) {
        return sendVoid(request(endpoint, "PATCH", body, null, actingStaffId));
    }

    private HttpRequest request(String endpoint, String method, byte @Nullable [] body, @Nullable Duration timeout,
                                @Nullable String actingStaffId) {
        return newRequest(basePath + endpoint, method, body, timeout, protoHeaders(body, actingStaffId));
    }

    private Map<String, String> protoHeaders(byte @Nullable [] body, @Nullable String actingStaffId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER_ACCEPT, CONTENT_TYPE_PROTOBUF);
        if (actingStaffId != null && !actingStaffId.trim().isEmpty()) headers.put(HEADER_ACTING_STAFF_ID, actingStaffId);
        if (body != null) headers.put(HEADER_CONTENT_TYPE, CONTENT_TYPE_PROTOBUF);
        return headers;
    }

    private <P extends Message, R> CompletableFuture<R> send(HttpRequest request, Parser<P> parser,
                                                             Function<P, R> mapper, @Nullable String operation,
                                                             RequestLane lane) {
        return execute(request, operation, lane, (requestId, body) -> decodeProto(requestId, body, parser, mapper));
    }

    private CompletableFuture<Void> sendVoid(HttpRequest request) {
        return execute(request, null, backgroundLane, (requestId, body) -> body).thenAccept(ignored -> { });
    }

    private <P extends Message, R> R decodeProto(String requestId, byte[] body, Parser<P> parser, Function<P, R> mapper) {
        try {
            return mapper.apply(parser.parseFrom(body));
        } catch (InvalidProtocolBufferException e) {
            logger.severe(String.format("[V3-REQ-%s] Failed to parse response: %s", requestId, e.getMessage()));
            throw new RuntimeException("Failed to parse V3 response: " + e.getMessage(), e);
        }
    }

    @Override
    protected void logRequest(String requestId, HttpRequest request) {
        logger.info(String.format("[V3-REQ-%s] %s %s", requestId, request.method, request.url));
        if (request.body != null) logger.info(String.format("[V3-REQ-%s] Body present (%d bytes)", requestId, request.body.length));
    }

    @Override
    protected void logResponse(String requestId, int statusCode, byte[] body, long durationMs,
                               @Nullable String operation) {
        logger.info(String.format("[V3-RES-%s] Status: %d (%d bytes, took %dms)", requestId, statusCode, body.length, durationMs));
    }

    @Override
    protected FailureBody readFailure(int statusCode, byte[] responseBody) {
        String fallback = String.format("V3 request failed: HTTP %d", statusCode);
        try {
            ApiError error = ApiError.parseFrom(responseBody);
            return new FailureBody(error.getMessage().isEmpty() ? fallback : error.getMessage(), error.getCode());
        } catch (InvalidProtocolBufferException unreadable) {
            return new FailureBody(fallback, null);
        }
    }
}
