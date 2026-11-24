package gg.modl.backend.staff.controller;

import gg.modl.backend.auth.AuthResponseMessage;
import gg.modl.backend.auth.controller.SignInProtoMapper;
import gg.modl.backend.auth.controller.SignInResponder;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.signin.SignInAttempt;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.service.InvitationPreview;
import gg.modl.backend.staff.service.InvitationService;
import gg.modl.proto.modl.v1.AcceptInvitationRequest;
import gg.modl.proto.modl.v1.InspectInvitationRequest;
import gg.modl.proto.modl.v1.InspectInvitationResponse;
import gg.modl.proto.modl.v1.PanelAuthResponse;
import gg.modl.proto.modl.v1.PanelResource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PUBLIC_STAFF)
@RequiredArgsConstructor
public class PublicStaffController {
    private final InvitationService invitationService;
    private final RealtimeEventPublisher realtimeEventPublisher;
    private final SignInResponder signInResponder;

    @PostMapping("/invitations/inspect")
    public InspectInvitationResponse inspectInvitation(@RequestBody InspectInvitationRequest body, HttpServletRequest request) {
        InvitationPreview preview = invitationService.inspect(RequestUtil.getRequestServer(request), body.getToken());
        return InspectInvitationResponse.newBuilder()
            .setEmail(preview.email())
            .setMethod(SignInProtoMapper.toProto(preview.method()))
            .build();
    }

    @PostMapping("/invitations/accept")
    public PanelAuthResponse acceptInvitation(
        @RequestBody AcceptInvitationRequest body,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        Server server = RequestUtil.getRequestServer(request);
        Staff staff = invitationService.acceptInvitation(server, body.getToken(), body.hasPassword() ? body.getPassword() : null);
        realtimeEventPublisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_STAFF);
        return signInResponder.signIn(server, SignInAttempt.of(staff.getEmail(), AuthMethod.INVITATION),
            AuthResponseMessage.INVITATION_ACCEPTED, request, response);
    }
}
