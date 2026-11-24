package gg.modl.backend.ticket.controller;

import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.player.service.IssuerNameResolver;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.dto.request.BulkTicketUpdateRequest;
import gg.modl.backend.ticket.dto.request.CreateTicketRequest;
import gg.modl.backend.ticket.dto.request.QuickResponseRequest;
import gg.modl.backend.ticket.dto.request.UpdateTicketRequest;
import gg.modl.backend.ticket.dto.response.PaginatedTicketsResponse;
import gg.modl.backend.ticket.dto.response.QuickResponseResult;
import gg.modl.backend.ticket.dto.response.TicketResponse;
import gg.modl.backend.ticket.service.TicketNoteVisibility;
import gg.modl.backend.ticket.service.TicketReplyService;
import gg.modl.backend.ticket.service.TicketSearchService;
import gg.modl.backend.ticket.service.TicketService;
import gg.modl.backend.ticket.service.TicketSubscriptionService;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.proto.modl.v1.AddNoteRequest;
import gg.modl.proto.modl.v1.AddReplyRequest;
import gg.modl.proto.modl.v1.AddTagRequest;
import gg.modl.proto.modl.v1.AddTicketReplyResponse;
import gg.modl.proto.modl.v1.BulkTicketUpdateResponse;
import gg.modl.proto.modl.v1.PanelResource;
import gg.modl.proto.modl.v1.TicketCountsResponse;
import gg.modl.proto.modl.v1.TicketNote;
import gg.modl.proto.modl.v1.TicketTagsResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_TICKETS)
@RequiresPanelPermission(view = PermissionService.TICKET_VIEW_ALL, modify = PermissionService.TICKET_REPLY_ALL)
@RequiredArgsConstructor
@Validated
public class PanelTicketController {
    private final TicketService ticketService;
    private final TicketSearchService ticketSearchService;
    private final TicketReplyService ticketReplyService;
    private final TicketSubscriptionService subscriptionService;
    private final RealtimeEventPublisher realtimeEventPublisher;
    private final PanelActionAuditor panelActionAuditor;
    private final IssuerNameResolver issuerNameResolver;
    private final TicketNoteVisibility noteVisibility;

    @GetMapping
    public ResponseEntity<gg.modl.proto.modl.v1.PaginatedTicketsResponse> searchTickets(
        @RequestParam(defaultValue = "1") @Min(RequestValidationLimits.PAGINATION_PAGE_MIN) int page,
        @RequestParam(defaultValue = "10") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) int limit,
        @RequestParam(required = false) @Nullable String search,
        @RequestParam(required = false) @Nullable String status,
        @RequestParam(required = false) @Nullable List<String> type,
        @RequestParam(required = false) @Nullable String author,
        @RequestParam(required = false) @Nullable List<String> labels,
        @RequestParam(required = false) @Nullable List<String> assignee,
        @RequestParam(defaultValue = "newest") String sort,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        PaginatedTicketsResponse response = ticketSearchService.searchTickets(
            server, page, limit, search, status, type, author, labels, assignee, sort);
        return ResponseEntity.ok(PanelTicketProtoMapper.toPaginatedTicketsResponse(response));
    }

    @GetMapping("/counts")
    public ResponseEntity<TicketCountsResponse> getTicketCounts(
        @RequestParam(required = false) @Nullable String search,
        @RequestParam(required = false) @Nullable List<String> type,
        @RequestParam(required = false) @Nullable String author,
        @RequestParam(required = false) @Nullable List<String> labels,
        @RequestParam(required = false) @Nullable List<String> assignee,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        Map<String, Long> counts = ticketSearchService.getTicketCounts(server, search, type, author, labels, assignee);
        return ResponseEntity.ok(PanelTicketProtoMapper.toTicketCountsResponse(counts));
    }

    @PostMapping("/bulk")
    @RequiresPanelPermission(view = PermissionService.TICKET_VIEW_ALL, modify = PermissionService.TICKET_CLOSE_ALL)
    public ResponseEntity<BulkTicketUpdateResponse> bulkUpdateTickets(
        @RequestBody gg.modl.proto.modl.v1.BulkTicketUpdateRequest bulkRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String staffEmail = RequestUtil.requireSessionEmail(request);

        BulkTicketUpdateRequest command = PanelTicketProtoMapper.fromBulkTicketUpdateRequest(bulkRequest);
        if (command.ticketIds() == null || command.ticketIds().isEmpty()) {
            throw new ValidationException("No ticket IDs provided");
        }
        int updatedCount = ticketService.bulkUpdateTickets(server, command, staffEmail);
        realtimeEventPublisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_TICKETS);
        panelActionAuditor.recordStaffAction(server, staffEmail, "Bulk updated " + updatedCount + " ticket(s)");
        return ResponseEntity.ok(PanelTicketProtoMapper.toBulkTicketUpdateResponse(
            updatedCount, "Successfully updated " + updatedCount + " tickets"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<gg.modl.proto.modl.v1.TicketResponse> getTicket(
        @PathVariable String id,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String staffEmail = RequestUtil.requireSessionEmail(request);

        TicketResponse ticket = ticketService.getTicketById(server, id);
        if (!staffEmail.isBlank()) {
            subscriptionService.markTicketAsRead(server, id, staffEmail);
        }

        return ResponseEntity.ok(PanelTicketProtoMapper.toTicketResponse(noteVisibility.visibleTo(server, staffEmail, ticket)));
    }

    @PostMapping
    public ResponseEntity<gg.modl.proto.modl.v1.TicketResponse> createTicket(
        @RequestBody gg.modl.proto.modl.v1.CreateTicketRequest createRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        TicketResponse ticket = ticketService.createTicket(server, PanelTicketProtoMapper.fromCreateTicketRequest(createRequest));
        realtimeEventPublisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_TICKETS, ticket.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(PanelTicketProtoMapper.toTicketResponse(
            noteVisibility.visibleTo(server, RequestUtil.requireSessionEmail(request), ticket)));
    }

    @PatchMapping("/{id}")
    @RequiresPanelPermission(PermissionService.TICKET_VIEW_ALL)
    public ResponseEntity<gg.modl.proto.modl.v1.TicketResponse> updateTicket(
        @PathVariable String id,
        @RequestBody gg.modl.proto.modl.v1.UpdateTicketRequest updateRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String staffEmail = RequestUtil.requireSessionEmail(request);

        UpdateTicketRequest command = PanelTicketProtoMapper.fromUpdateTicketRequest(
            updateRequest, sessionStaffName(server, request));
        TicketResponse ticket = ticketService.updateTicket(server, id, command, staffEmail);
        realtimeEventPublisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_TICKETS, ticket.id());
        panelActionAuditor.recordStaffAction(server, staffEmail, "Updated ticket " + id);
        return ResponseEntity.ok(PanelTicketProtoMapper.toTicketResponse(noteVisibility.visibleTo(server, staffEmail, ticket)));
    }

    @PostMapping("/{id}/notes")
    @RequiresPanelPermission(PermissionService.TICKET_VIEW_ALL)
    public ResponseEntity<TicketNote> addNote(
        @PathVariable String id,
        @RequestBody AddNoteRequest noteRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String staffEmail = RequestUtil.requireSessionEmail(request);

        gg.modl.backend.ticket.data.TicketNote note =
            ticketReplyService.addNote(server, id,
                PanelTicketProtoMapper.fromAddNoteRequest(noteRequest), sessionStaffName(server, request), staffEmail);
        realtimeEventPublisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_TICKETS, id);
        panelActionAuditor.recordStaffAction(server, staffEmail, "Added a staff note to ticket " + id);
        return ResponseEntity.status(HttpStatus.CREATED).body(PanelTicketProtoMapper.toTicketNoteResponse(note));
    }

    @PostMapping("/{id}/replies")
    public ResponseEntity<AddTicketReplyResponse> addReply(
        @PathVariable String id,
        @RequestBody AddReplyRequest replyRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String staffEmail = RequestUtil.requireSessionEmail(request);

        gg.modl.backend.ticket.dto.request.AddReplyRequest command =
            PanelTicketProtoMapper.fromAddReplyRequest(replyRequest, sessionStaffName(server, request));
        TicketReply reply = ticketReplyService.addReply(server, id, command);
        subscriptionService.ensureSubscription(server, id, staffEmail);

        realtimeEventPublisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_TICKETS, id);
        panelActionAuditor.recordStaffAction(server, staffEmail, "Replied to ticket " + id);
        return ResponseEntity.status(HttpStatus.CREATED).body(PanelTicketProtoMapper.toAddReplyResponse(reply));
    }

    @PostMapping("/{id}/tags")
    @RequiresPanelPermission(view = PermissionService.TICKET_VIEW_ALL, modify = PermissionService.TICKET_MANAGE_TAGS)
    public ResponseEntity<TicketTagsResponse> addTag(
        @PathVariable String id,
        @RequestBody AddTagRequest tagRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        List<String> tags = ticketReplyService.addTag(server, id, tagRequest.getTag());
        realtimeEventPublisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_TICKETS, id);
        return ResponseEntity.ok(PanelTicketProtoMapper.toTagsResponse(tags));
    }

    @DeleteMapping("/{id}/tags/{tag}")
    @RequiresPanelPermission(view = PermissionService.TICKET_VIEW_ALL, modify = PermissionService.TICKET_MANAGE_TAGS)
    public ResponseEntity<TicketTagsResponse> removeTag(
        @PathVariable String id,
        @PathVariable String tag,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        List<String> tags = ticketReplyService.removeTag(server, id, tag);
        realtimeEventPublisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_TICKETS, id);
        return ResponseEntity.ok(PanelTicketProtoMapper.toTagsResponse(tags));
    }

    @GetMapping("/player/{uuid}")
    public ResponseEntity<?> getTicketsByPlayer(
        @PathVariable String uuid,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        return ResponseEntity.ok(ticketSearchService.getTicketsByPlayer(server, uuid));
    }

    @GetMapping("/tag/{tag}")
    public ResponseEntity<?> getTicketsByTag(
        @PathVariable String tag,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        return ResponseEntity.ok(ticketSearchService.getTicketsByTag(server, tag));
    }

    @PostMapping("/{id}/quick-response")
    public ResponseEntity<gg.modl.proto.modl.v1.QuickResponseResult> quickResponse(
        @PathVariable String id,
        @RequestBody gg.modl.proto.modl.v1.QuickResponseRequest quickRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String staffEmail = RequestUtil.requireSessionEmail(request);
        QuickResponseRequest command = PanelTicketProtoMapper.fromQuickResponseRequest(quickRequest);
        QuickResponseResult result =
            ticketService.processQuickResponse(server, id, command, staffEmail, sessionStaffName(server, request));

        if (!result.success()) {
            return ResponseEntity.badRequest().body(PanelTicketProtoMapper.toQuickResponseResult(result));
        }

        realtimeEventPublisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_TICKETS, id);
        return ResponseEntity.ok(PanelTicketProtoMapper.toQuickResponseResult(result));
    }

    private String sessionStaffName(Server server, HttpServletRequest request) {
        return issuerNameResolver.panelIssuer(server, RequestUtil.requireSessionEmail(request)).name();
    }
}
