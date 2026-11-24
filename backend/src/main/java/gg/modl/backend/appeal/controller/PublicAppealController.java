package gg.modl.backend.appeal.controller;

import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.valuesToObjects;

import gg.modl.backend.appeal.service.AppealService;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.web.ClientIpResolver;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.controller.PublicVerificationProtoMapper;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.dto.response.TicketResponse;
import gg.modl.backend.ticket.service.PublicRecordAccessService;
import gg.modl.backend.ticket.service.PublicRecordVerificationService;
import gg.modl.proto.modl.v1.AddAppealReplyRequest;
import gg.modl.proto.modl.v1.AddPublicAppealReplyResponse;
import gg.modl.proto.modl.v1.CreateAppealRequest;
import gg.modl.proto.modl.v1.CreatePublicAppealResponse;
import gg.modl.proto.modl.v1.PanelResource;
import gg.modl.proto.modl.v1.PublicAppealResponse;
import gg.modl.proto.modl.v1.PublicTicketVerificationRequestResponse;
import gg.modl.proto.modl.v1.PublicTicketVerificationResponse;
import gg.modl.proto.modl.v1.VerifyTicketCodeRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
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
@RequestMapping(RESTMappingV1.PUBLIC_APPEALS)
@RequiredArgsConstructor
public class PublicAppealController {
    private final AppealService appealService;
    private final PublicRecordAccessService recordAccessService;
    private final PublicRecordVerificationService recordVerificationService;
    private final RealtimeEventPublisher realtimeEventPublisher;
    private final ClientIpResolver clientIpResolver;

    @GetMapping("/{id}")
    public ResponseEntity<PublicAppealResponse> getAppeal(
        @PathVariable String id,
        @RequestParam(value = "token", required = false) String appealToken,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        Ticket appeal = requireAccess(server, id, appealToken);

        TicketResponse appealResponse = appealService.toResponse(server, appeal);
        return ResponseEntity.ok(PublicAppealProtoMapper.toPublicAppealResponse(appealResponse));
    }

    @PostMapping
    public ResponseEntity<CreatePublicAppealResponse> createAppeal(
        @RequestBody CreateAppealRequest createRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        TicketResponse appeal = appealService.createAppeal(server, PanelAppealProtoMapper.fromCreateAppealRequest(createRequest));
        realtimeEventPublisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_APPEALS, appeal.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(PublicAppealProtoMapper.toCreateAppealResponse(appeal));
    }

    @PostMapping("/{id}/replies")
    public ResponseEntity<AddPublicAppealReplyResponse> addReply(
        @PathVariable String id,
        @RequestBody AddAppealReplyRequest replyRequest,
        @RequestParam(value = "token", required = false) String appealToken,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        requireAccess(server, id, appealToken);

        List<Object> attachments = valuesToObjects(replyRequest.getAttachmentsList());
        TicketReply reply = appealService.addPublicReply(server, id, replyRequest.getContent(), attachments);
        realtimeEventPublisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_APPEALS, id);
        return ResponseEntity.status(HttpStatus.CREATED).body(PublicAppealProtoMapper.toAddReplyResponse(reply));
    }

    @PostMapping("/{id}/request-verification")
    public ResponseEntity<PublicTicketVerificationRequestResponse> requestVerification(
        @PathVariable String id,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        Ticket appeal = recordVerificationService.requireVerifiableRecord(appealService.getAppealRaw(server, id).orElse(null));

        String emailHint = recordVerificationService.sendVerificationCode(server, appeal);
        return ResponseEntity.ok(PublicVerificationProtoMapper.toRequestVerificationResponse(emailHint));
    }

    @PostMapping("/{id}/verify")
    public ResponseEntity<PublicTicketVerificationResponse> verifyCode(
        @PathVariable String id,
        @RequestBody VerifyTicketCodeRequest body,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        recordVerificationService.requireVerifiableRecord(appealService.getAppealRaw(server, id).orElse(null));

        String token = recordVerificationService.verifyCode(
            server, id, body.getCode(), clientIpResolver.resolveNetwork(request));
        return ResponseEntity.ok(PublicVerificationProtoMapper.toVerifyResponse(token));
    }

    private Ticket requireAccess(Server server, String id, String token) {
        return recordAccessService.requireAccess(server, appealService.getAppealRaw(server, id).orElse(null), token).record();
    }
}
