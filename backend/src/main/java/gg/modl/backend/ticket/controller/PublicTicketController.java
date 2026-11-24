package gg.modl.backend.ticket.controller;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.web.ClientIpResolver;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.dto.response.TicketResponse;
import gg.modl.backend.ticket.service.PublicRecordAccessService;
import gg.modl.backend.ticket.service.PublicRecordAccessService.AccessGrant;
import gg.modl.backend.ticket.service.PublicRecordVerificationService;
import gg.modl.backend.ticket.service.TicketReplyService;
import gg.modl.backend.ticket.service.TicketService;
import gg.modl.proto.modl.v1.AddReplyRequest;
import gg.modl.proto.modl.v1.AddTicketReplyResponse;
import gg.modl.proto.modl.v1.CreateTicketRequest;
import gg.modl.proto.modl.v1.CreateTicketResponse;
import gg.modl.proto.modl.v1.PanelResource;
import gg.modl.proto.modl.v1.PublicTicketResponse;
import gg.modl.proto.modl.v1.PublicTicketStatusResponse;
import gg.modl.proto.modl.v1.PublicTicketVerificationRequestResponse;
import gg.modl.proto.modl.v1.PublicTicketVerificationResponse;
import gg.modl.proto.modl.v1.SubmitPublicTicketResponse;
import gg.modl.proto.modl.v1.SubmitTicketFormRequest;
import gg.modl.proto.modl.v1.VerifyTicketCodeRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PUBLIC_TICKETS)
@RequiredArgsConstructor
public class PublicTicketController {
    private final TicketService ticketService;
    private final TicketReplyService ticketReplyService;
    private final PublicRecordAccessService recordAccessService;
    private final PublicRecordVerificationService recordVerificationService;
    private final RealtimeEventPublisher realtimeEventPublisher;
    private final ClientIpResolver clientIpResolver;

    @PostMapping
    public ResponseEntity<CreateTicketResponse> createTicket(
        @RequestBody CreateTicketRequest createRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        TicketResponse ticket = ticketService.createTicket(server, PublicTicketProtoMapper.fromPublicCreateTicketRequest(createRequest));
        realtimeEventPublisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_TICKETS, ticket.id());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(PublicTicketProtoMapper.toCreateTicketResponse(ticket, "Ticket created successfully"));
    }

    @PostMapping("/unfinished")
    public ResponseEntity<CreateTicketResponse> createUnfinishedTicket(
        @RequestBody CreateTicketRequest createRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        TicketResponse ticket = ticketService.createUnfinishedTicket(server, PublicTicketProtoMapper.fromPublicCreateTicketRequest(createRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(PublicTicketProtoMapper.toCreateTicketResponse(ticket, "Ticket created successfully (Unfinished)"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PublicTicketResponse> getTicket(
        @PathVariable String id,
        @RequestParam(value = "token", required = false) @Nullable String ticketToken,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        AccessGrant grant = requireAccess(server, id, ticketToken);
        Ticket ticket = grant.record();

        TicketResponse ticketResponse = ticketService.toResponse(server, ticket);
        Set<String> formFieldAllowlist = ticketService.getPublicFormFieldIds(server, ticket);
        return ResponseEntity.ok(PublicTicketProtoMapper.toPublicTicketResponse(
            ticketResponse, ticket, formFieldAllowlist, grant.emailVerified()));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<PublicTicketStatusResponse> getTicketStatus(
        @PathVariable String id,
        @RequestParam(value = "token", required = false) @Nullable String ticketToken,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        Ticket ticket = requireAccess(server, id, ticketToken).record();

        return ResponseEntity.ok(PublicTicketProtoMapper.toStatusResponse(ticketService.toResponse(server, ticket)));
    }

    @PostMapping("/{id}/replies")
    public ResponseEntity<AddTicketReplyResponse> addReply(
        @PathVariable String id,
        @RequestBody AddReplyRequest replyRequest,
        @RequestParam(value = "token", required = false) @Nullable String ticketToken,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        requireAccess(server, id, ticketToken);

        List<Object> attachments = PublicTicketProtoMapper.attachmentsFromReply(replyRequest);
        TicketReply reply = ticketReplyService.addPublicReply(server, id, replyRequest.getContent(), attachments);
        realtimeEventPublisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_TICKETS, id);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(AddTicketReplyResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Reply added successfully")
                .setReply(PublicTicketProtoMapper.toPublicReply(reply))
                .build());
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<SubmitPublicTicketResponse> submitTicketForm(
        @PathVariable String id,
        @RequestBody SubmitTicketFormRequest submitRequest,
        @RequestParam(value = "token", required = false) @Nullable String ticketToken,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        AccessGrant grant = requireAccess(server, id, ticketToken);

        Ticket ticket = grant.record();
        if (ticket.isLocked() || (ticket.getStatus() != null && ticket.getStatus().isTerminal())) {
            throw new ForbiddenException("Ticket is closed and cannot be resubmitted");
        }

        TicketResponse ticketResp = ticketService.submitTicketForm(
            server, id, PublicTicketProtoMapper.fromSubmitTicketFormRequest(submitRequest), grant.emailVerified());
        realtimeEventPublisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_TICKETS, id);
        return ResponseEntity.ok(PublicTicketProtoMapper.toSubmitResponse(ticketResp));
    }

    @PostMapping("/{id}/request-verification")
    public ResponseEntity<PublicTicketVerificationRequestResponse> requestVerification(
        @PathVariable String id,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        Ticket ticket = recordVerificationService.requireVerifiableRecord(ticketService.getTicketRaw(server, id).orElse(null));

        String emailHint = recordVerificationService.sendVerificationCode(server, ticket);
        return ResponseEntity.ok(PublicVerificationProtoMapper.toRequestVerificationResponse(emailHint));
    }

    @PostMapping("/{id}/verify")
    public ResponseEntity<PublicTicketVerificationResponse> verifyCode(
        @PathVariable String id,
        @RequestBody VerifyTicketCodeRequest body,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        recordVerificationService.requireVerifiableRecord(ticketService.getTicketRaw(server, id).orElse(null));

        String token = recordVerificationService.verifyCode(
            server, id, body.getCode(), clientIpResolver.resolveNetwork(request));
        return ResponseEntity.ok(PublicVerificationProtoMapper.toVerifyResponse(token));
    }

    private AccessGrant requireAccess(Server server, String id, @Nullable String token) {
        return recordAccessService.requireAccess(server, ticketService.getTicketRaw(server, id).orElse(null), token);
    }
}
