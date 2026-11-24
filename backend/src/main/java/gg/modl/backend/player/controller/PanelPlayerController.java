package gg.modl.backend.player.controller;

import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.dto.request.AddModificationRequest;
import gg.modl.backend.player.dto.response.LinkedAccountResponse;
import gg.modl.backend.player.dto.response.PunishmentPreviewView;
import gg.modl.backend.player.dto.response.PunishmentResponse;
import gg.modl.backend.player.dto.response.PunishmentSearchResult;
import gg.modl.backend.player.service.AccountLinkingService;
import gg.modl.backend.player.service.IssuerNameResolver;
import gg.modl.backend.player.dto.response.LinkedBanView;
import gg.modl.backend.player.service.PunishmentEvidenceService;
import gg.modl.backend.player.service.PunishmentIssuer;
import gg.modl.backend.player.service.PunishmentLifecycleService;
import gg.modl.backend.player.service.PunishmentModificationAuthorizer;
import gg.modl.backend.player.service.PunishmentMutationService;
import gg.modl.backend.player.service.PunishmentQueryService;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.replay.service.PlayerReplayListingService;
import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.infrastructure.validation.RegExpConstants;
import gg.modl.proto.modl.v1.ActivePunishmentsResponse;
import gg.modl.proto.modl.v1.AddPlayerIpRequest;
import gg.modl.proto.modl.v1.AddPlayerUsernameRequest;
import gg.modl.proto.modl.v1.CreatePlayerNoteRequest;
import gg.modl.proto.modl.v1.CreatePlayerRequest;
import gg.modl.proto.modl.v1.ModifyPunishmentTicketsRequest;
import gg.modl.proto.modl.v1.PanelAddEvidenceRequest;
import gg.modl.proto.modl.v1.PanelAddModificationRequest;
import gg.modl.proto.modl.v1.PanelAddPunishmentNoteRequest;
import gg.modl.proto.modl.v1.PanelCreatePunishmentRequest;
import gg.modl.proto.modl.v1.PanelFindAndLinkAccountsResponse;
import gg.modl.proto.modl.v1.PanelLinkedAccountsResponse;
import gg.modl.proto.modl.v1.PanelLinkedBansResponse;
import gg.modl.proto.modl.v1.PlayerDetailResponse;
import gg.modl.proto.modl.v1.PlayerReplaysResponse;
import gg.modl.proto.modl.v1.PlayerSearchResultsResponse;
import gg.modl.proto.modl.v1.PunishmentPreviewResponse;
import gg.modl.proto.modl.v1.SimpleResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_PLAYERS)
@RequiresPanelPermission(rule = PanelAccessRule.PLAYER_ACCESS)
@RequiredArgsConstructor
@Validated
public class PanelPlayerController {
    private static final SimpleResponse SUCCESS = SimpleResponse.newBuilder().setSuccess(true).build();

    private final PlayerService playerService;
    private final IssuerNameResolver issuerNameResolver;
    private final PunishmentQueryService punishmentQueryService;
    private final PunishmentLifecycleService punishmentLifecycleService;
    private final PunishmentEvidenceService punishmentEvidenceService;
    private final PunishmentMutationService punishmentMutationService;
    private final AccountLinkingService accountLinkingService;
    private final PlayerReplayListingService playerReplayListingService;
    private final PunishmentModificationAuthorizer modificationAuthorizer;

    @GetMapping
    public ResponseEntity<PlayerSearchResultsResponse> searchPlayers(
        @RequestParam @Size(min = 2) String search,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        return ResponseEntity.ok(PanelPlayerProtoMapper.toPlayerSearchResults(
            playerService.searchPlayers(server, search)));
    }

    @GetMapping("/{uuid}")
    public ResponseEntity<PlayerDetailResponse> getPlayer(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        return ResponseEntity.ok(PanelPlayerProtoMapper.toPlayerDetail(
            playerService.getPlayerDetails(server, UUID.fromString(uuid))));
    }

    @PostMapping
    public ResponseEntity<SimpleResponse> createPlayer(
        @RequestBody CreatePlayerRequest createRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        playerService.createPlayer(
            server,
            UUID.fromString(createRequest.getMinecraftUuid()),
            createRequest.getUsername()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(SUCCESS);
    }

    @PostMapping("/{uuid}/usernames")
    public ResponseEntity<SimpleResponse> addUsername(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @RequestBody AddPlayerUsernameRequest addRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        playerService.addUsername(server, UUID.fromString(uuid), addRequest.getUsername());
        return ResponseEntity.ok(SUCCESS);
    }

    @PostMapping("/{uuid}/notes")
    @RequiresPanelPermission(PermissionService.PLAYER_NOTE)
    public ResponseEntity<SimpleResponse> addNote(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @RequestBody CreatePlayerNoteRequest addRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        PunishmentIssuer issuer = sessionIssuer(server, request);
        playerService.addNote(server, UUID.fromString(uuid), addRequest.getText(), issuer.name(), issuer.id());
        return ResponseEntity.ok(SUCCESS);
    }

    @PostMapping("/{uuid}/ips")
    public ResponseEntity<SimpleResponse> addIp(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @RequestBody AddPlayerIpRequest addRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        playerService.addIp(server, UUID.fromString(uuid), addRequest.getIpAddress());
        return ResponseEntity.ok(SUCCESS);
    }

    @PostMapping("/{uuid}/punishments")
    @RequiresPanelPermission(rule = PanelAccessRule.PERMIT_ALL)
    public ResponseEntity<SimpleResponse> createPunishment(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @RequestBody PanelCreatePunishmentRequest createRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String email = RequestUtil.requireSessionEmail(request);

        punishmentLifecycleService.validatePunishmentPermission(server, email, createRequest.getTypeOrdinal());
        punishmentLifecycleService.createPanelPunishment(
            server,
            UUID.fromString(uuid),
            issuerNameResolver.panelIssuer(server, email),
            PanelPlayerProtoMapper.fromCreatePunishment(createRequest)
        );
        return ResponseEntity.ok(SUCCESS);
    }

    @PostMapping("/{uuid}/punishments/{punishmentId}/modifications")
    @RequiresPanelPermission(rule = PanelAccessRule.PUNISHMENT_MODIFICATION)
    public ResponseEntity<SimpleResponse> addModification(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @PathVariable String punishmentId,
        @RequestBody PanelAddModificationRequest modRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        AddModificationRequest modification = PanelPlayerProtoMapper.fromAddModification(modRequest, sessionIssuer(server, request));
        String staffEmail = RequestUtil.getSessionEmail(request);
        modificationAuthorizer.requirePermitted(server, staffEmail, modification);
        punishmentMutationService.addModification(
            server,
            UUID.fromString(uuid),
            punishmentId,
            modification,
            modificationAuthorizer.shorteningBy(server, staffEmail)
        );
        return ResponseEntity.ok(SUCCESS);
    }

    @GetMapping("/{uuid}/punishments/active")
    public ResponseEntity<ActivePunishmentsResponse> getActivePunishments(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        List<PunishmentResponse> punishments = punishmentQueryService.getActivePunishments(
            server,
            UUID.fromString(uuid)
        );

        return ResponseEntity.ok(PanelPlayerProtoMapper.toActivePunishments(punishments));
    }

    @GetMapping("/{uuid}/punishments/preview")
    public ResponseEntity<PunishmentPreviewResponse> previewPunishment(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @RequestParam int typeOrdinal,
        @RequestParam(required = false) String offenseLevel,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        PunishmentPreviewView preview = punishmentQueryService.previewPunishment(server, uuid, typeOrdinal, offenseLevel);
        return ResponseEntity.ok(PunishmentPreviewProtoMapper.toProto(preview));
    }

    @GetMapping("/punishments/{punishmentId}")
    public ResponseEntity<gg.modl.proto.modl.v1.PunishmentResponse> getPunishmentById(
        @PathVariable String punishmentId,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        return ResponseEntity.ok(PanelPlayerProtoMapper.toPunishment(
            punishmentQueryService.getPunishmentById(server, punishmentId)));
    }

    @GetMapping("/punishments/search")
    public ResponseEntity<List<PunishmentSearchResult>> searchPunishments(
        @RequestParam @Size(min = 2) String q,
        @RequestParam(defaultValue = "false") boolean activeOnly,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        return ResponseEntity.ok(punishmentQueryService.searchPunishments(server, q, activeOnly));
    }

    @PostMapping("/{uuid}/punishments/{punishmentId}/notes")
    @RequiresPanelPermission(PermissionService.PUNISHMENT_MODIFY_NOTE)
    public ResponseEntity<SimpleResponse> addPunishmentNote(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @PathVariable String punishmentId,
        @RequestBody PanelAddPunishmentNoteRequest noteRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        PunishmentIssuer issuer = sessionIssuer(server, request);
        punishmentEvidenceService.addPunishmentNote(
            server,
            UUID.fromString(uuid),
            punishmentId,
            noteRequest.getText(),
            issuer.name(),
            issuer.id()
        );
        return ResponseEntity.ok(SUCCESS);
    }

    @PostMapping("/{uuid}/punishments/{punishmentId}/evidence")
    @RequiresPanelPermission(PermissionService.PUNISHMENT_MODIFY_EVIDENCE)
    public ResponseEntity<SimpleResponse> addEvidence(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @PathVariable String punishmentId,
        @RequestBody PanelAddEvidenceRequest evidenceRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        punishmentEvidenceService.addEvidence(
            server,
            UUID.fromString(uuid),
            punishmentId,
            PanelPlayerProtoMapper.fromAddEvidence(evidenceRequest, sessionIssuer(server, request))
        );
        return ResponseEntity.ok(SUCCESS);
    }

    @PostMapping("/{uuid}/punishments/{punishmentId}/tickets")
    @RequiresPanelPermission(PermissionService.PUNISHMENT_MODIFY_TICKETS)
    public ResponseEntity<SimpleResponse> modifyPunishmentTickets(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @PathVariable String punishmentId,
        @RequestBody ModifyPunishmentTicketsRequest ticketRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        punishmentMutationService.modifyPunishmentTickets(
            server,
            UUID.fromString(uuid),
            punishmentId,
            PanelPlayerProtoMapper.fromModifyTickets(ticketRequest, sessionIssuer(server, request))
        );
        return ResponseEntity.ok(SUCCESS);
    }

    @GetMapping("/{uuid}/linked")
    public ResponseEntity<PanelLinkedAccountsResponse> getLinkedAccounts(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        List<LinkedAccountResponse> linkedAccounts = accountLinkingService.getLinkedAccounts(
            server,
            UUID.fromString(uuid)
        );

        return ResponseEntity.ok(PanelPlayerProtoMapper.toLinkedAccounts(linkedAccounts));
    }

    @GetMapping("/{uuid}/replays")
    public ResponseEntity<PlayerReplaysResponse> getPlayerReplays(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        return ResponseEntity.ok(PanelPlayerProtoMapper.toPlayerReplays(
            playerReplayListingService.listPlayerReplays(server, uuid)));
    }

    @GetMapping("/punishments/{punishmentId}/linked-bans")
    public ResponseEntity<PanelLinkedBansResponse> getLinkedBans(
        @PathVariable String punishmentId,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        List<LinkedBanView> linkedBans = punishmentQueryService.getLinkedBansForParent(server, punishmentId);
        return ResponseEntity.ok(PanelPlayerProtoMapper.toLinkedBans(linkedBans));
    }

    @PostMapping("/{uuid}/find-linked")
    public ResponseEntity<PanelFindAndLinkAccountsResponse> findAndLinkAccounts(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        AccountLinkingService.LinkingResult result = accountLinkingService.findAndLinkAccounts(
            server,
            UUID.fromString(uuid)
        );

        return ResponseEntity.ok(PanelPlayerProtoMapper.toFindAndLinkResult(
            result.success(),
            result.message(),
            result.linkedAccountsFound()
        ));
    }

    private PunishmentIssuer sessionIssuer(Server server, HttpServletRequest request) {
        return issuerNameResolver.panelIssuer(server, RequestUtil.getSessionEmail(request));
    }
}
