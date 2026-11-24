package gg.modl.backend.audit.controller;

import gg.modl.backend.audit.service.AdminDatabaseBrowserService;
import gg.modl.backend.audit.service.AuditService;
import gg.modl.backend.audit.service.PunishmentAuditQueryService;
import gg.modl.backend.audit.service.PunishmentListRequest;
import gg.modl.backend.audit.service.StaffPerformanceService;
import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.audit.dto.response.DatabaseTablePage;
import gg.modl.backend.audit.dto.response.PunishmentAuditPage;
import gg.modl.backend.audit.dto.response.StaffDetailsResponse;
import gg.modl.backend.audit.dto.response.StaffPerformanceResponse;
import gg.modl.backend.player.service.IssuerNameResolver;
import gg.modl.backend.player.service.PunishmentIssuer;
import gg.modl.backend.player.service.PunishmentModificationAuthorizer;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.AuditBulkOperationResponse;
import gg.modl.proto.modl.v1.AuditDatabaseTableResponse;
import gg.modl.proto.modl.v1.AuditPunishmentTypesResponse;
import gg.modl.proto.modl.v1.AuditPunishmentsResponse;
import gg.modl.proto.modl.v1.AuditRollbackResponse;
import gg.modl.proto.modl.v1.BulkPunishmentActionRequest;
import gg.modl.proto.modl.v1.DateRangeRollbackRequest;
import gg.modl.proto.modl.v1.PanelResource;
import gg.modl.proto.modl.v1.RollbackRequest;
import gg.modl.proto.modl.v1.StaffPerformanceListResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_AUDIT)
@RequiresPanelPermission(PermissionService.ADMIN_AUDIT_VIEW_LOGS)
@RequiredArgsConstructor
@Validated
public class AuditController {
    private final AuditService auditService;
    private final PunishmentAuditQueryService punishmentAuditQueryService;
    private final AdminDatabaseBrowserService adminDatabaseBrowserService;
    private final StaffPerformanceService staffPerformanceService;
    private final RealtimeEventPublisher realtimeEventPublisher;
    private final IssuerNameResolver issuerNameResolver;
    private final PunishmentModificationAuthorizer modificationAuthorizer;

    @GetMapping("/staff-performance")
    public ResponseEntity<StaffPerformanceListResponse> getStaffPerformance(
        @RequestParam(defaultValue = "30d") String period,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        List<StaffPerformanceResponse> performance = staffPerformanceService.getStaffPerformance(server, period);
        return ResponseEntity.ok(AuditProtoMapper.toStaffPerformanceList(performance));
    }

    @GetMapping("/staff/{username}/details")
    public ResponseEntity<gg.modl.proto.modl.v1.StaffDetailsResponse> getStaffDetails(
        @PathVariable String username,
        @RequestParam(defaultValue = "30d") String period,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        StaffDetailsResponse details = staffPerformanceService.getStaffDetails(server, username, period);
        return ResponseEntity.ok(AuditProtoMapper.toStaffDetails(details));
    }

    @GetMapping("/punishments")
    public ResponseEntity<AuditPunishmentsResponse> getPunishments(
        @RequestParam(defaultValue = "active") String status,
        @RequestParam(defaultValue = "issued") String sort,
        @RequestParam(defaultValue = "desc") String direction,
        @RequestParam(required = false) String staff,
        @RequestParam(required = false) Integer type,
        @RequestParam(defaultValue = "all") String evidence,
        @RequestParam(defaultValue = "" + PunishmentAuditQueryService.DEFAULT_LIST_LIMIT) @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(PunishmentAuditQueryService.MAX_LIST_LIMIT) int limit,
        @RequestParam(required = false) String cursor,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        PunishmentListRequest listRequest = new PunishmentListRequest(status, sort, direction, staff, type, evidence);
        PunishmentAuditPage page = punishmentAuditQueryService.getPunishmentsList(server, listRequest, limit, cursor);
        return ResponseEntity.ok(AuditProtoMapper.toPunishments(page));
    }

    @GetMapping("/punishments/types")
    public ResponseEntity<AuditPunishmentTypesResponse> getPunishmentTypes(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        return ResponseEntity.ok(AuditProtoMapper.toPunishmentTypes(punishmentAuditQueryService.getPunishmentTypes(server)));
    }

    @GetMapping("/database/{table}")
    @RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
    public ResponseEntity<AuditDatabaseTableResponse> getDatabaseTable(
        @PathVariable String table,
        @RequestParam(defaultValue = "100") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int skip,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        DatabaseTablePage result = adminDatabaseBrowserService.getDatabaseTable(server, table, limit, skip);
        return ResponseEntity.ok(AuditProtoMapper.toDatabaseTableResponse(result));
    }

    @PostMapping("/punishments/{id}/rollback")
    @RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN, supersedesPermissions = PermissionService.ADMIN_AUDIT_ROLLBACK)
    public ResponseEntity<AuditRollbackResponse> rollbackPunishment(
        @PathVariable String id,
        @RequestBody(required = false) RollbackRequest rollbackRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        PunishmentIssuer performer = issuerNameResolver.panelIssuer(server, RequestUtil.getSessionEmail(request));

        String reason = rollbackRequest != null && rollbackRequest.hasReason()
            ? rollbackRequest.getReason() : "Admin rollback";
        boolean success = auditService.rollbackPunishment(server, id, reason, performer);

        if (success) {
            invalidateAudit(server);
            return ResponseEntity.ok(
                AuditProtoMapper.toRollbackResponse(true, "Punishment rolled back successfully"));
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/staff/{username}/rollback-all")
    @RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN, supersedesPermissions = PermissionService.ADMIN_AUDIT_ROLLBACK)
    public ResponseEntity<AuditBulkOperationResponse> rollbackAllByStaff(
        @PathVariable String username,
        @RequestBody(required = false) RollbackRequest rollbackRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        PunishmentIssuer performer = issuerNameResolver.panelIssuer(server, RequestUtil.getSessionEmail(request));

        String reason = rollbackRequest != null && rollbackRequest.hasReason()
            ? rollbackRequest.getReason() : "Bulk rollback by admin";
        int count = auditService.rollbackAllPunishmentsByStaff(server, username, reason, performer);

        invalidateAudit(server);
        return ResponseEntity.ok(AuditProtoMapper.toBulkOperationResponse(
            true, count, "Successfully rolled back " + count + " punishments"));
    }

    @PostMapping("/staff/{username}/rollback-date-range")
    @RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN, supersedesPermissions = PermissionService.ADMIN_AUDIT_ROLLBACK)
    public ResponseEntity<AuditBulkOperationResponse> rollbackByDateRange(
        @PathVariable String username,
        @RequestBody DateRangeRollbackRequest rollbackRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        PunishmentIssuer performer = issuerNameResolver.panelIssuer(server, RequestUtil.getSessionEmail(request));

        Instant startDate = AuditProtoMapper.toDate(rollbackRequest.getStartDate());
        Instant endDate = AuditProtoMapper.toDate(rollbackRequest.getEndDate());
        if (startDate == null || endDate == null) {
            throw new ValidationException("Start date and end date are required");
        }

        String reason = rollbackRequest.hasReason() ? rollbackRequest.getReason() : "Bulk rollback by admin";
        int count = auditService.rollbackPunishmentsByDateRange(
            server,
            username,
            startDate,
            endDate,
            reason,
            performer
        );

        invalidateAudit(server);
        return ResponseEntity.ok(AuditProtoMapper.toBulkOperationResponse(
            true, count, "Successfully rolled back " + count + " punishments"));
    }

    @PostMapping("/punishments/bulk-pardon")
    @RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN, supersedesPermissions = PermissionService.ADMIN_AUDIT_ROLLBACK)
    public ResponseEntity<AuditBulkOperationResponse> bulkPardon(
        @RequestBody BulkPunishmentActionRequest actionRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        PunishmentIssuer performer = issuerNameResolver.panelIssuer(server, RequestUtil.getSessionEmail(request));
        int count = auditService.bulkPardonByType(
            server, actionRequest.getTypeOrdinalsList(), actionRequest.getReason(), performer);

        invalidateAudit(server);
        return ResponseEntity.ok(AuditProtoMapper.toBulkOperationResponse(
            true, count, "Successfully pardoned " + count + " punishments"));
    }

    @PostMapping("/punishments/bulk-set-expiration")
    @RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN, supersedesPermissions = PermissionService.ADMIN_AUDIT_ROLLBACK)
    public ResponseEntity<AuditBulkOperationResponse> bulkSetExpiration(
        @RequestBody BulkPunishmentActionRequest actionRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        if (!actionRequest.hasNewDurationMs()) {
            throw new ValidationException("newDurationMs is required for set-expiration");
        }

        String staffEmail = RequestUtil.getSessionEmail(request);
        PunishmentIssuer performer = issuerNameResolver.panelIssuer(server, staffEmail);
        int count = auditService.bulkSetExpirationByType(
            server, actionRequest.getTypeOrdinalsList(), actionRequest.getNewDurationMs(),
            actionRequest.getReason(), performer, modificationAuthorizer.shorteningBy(server, staffEmail));

        invalidateAudit(server);
        return ResponseEntity.ok(AuditProtoMapper.toBulkOperationResponse(
            true, count, "Successfully updated expiration for " + count + " punishments"));
    }

    private void invalidateAudit(Server server) {
        realtimeEventPublisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_AUDIT);
    }
}
