package gg.modl.backend.instance.setup;

import gg.modl.backend.auth.AuthResponseMessage;
import gg.modl.backend.auth.controller.SignInResponder;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.signin.SignInAttempt;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.proto.modl.v1.ClaimSetupRequest;
import gg.modl.proto.modl.v1.PanelAuthResponse;
import gg.modl.proto.modl.v1.SetupOwnerSignInRequest;
import gg.modl.proto.modl.v1.SetupStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PUBLIC_SETUP)
@RequiredArgsConstructor
public class PublicSetupController {
    private final SetupClaimService setupClaimService;
    private final SetupStatusService setupStatusService;
    private final PublicUrlProbe publicUrlProbe;
    private final SignInResponder signInResponder;
    private final InstanceContext instanceContext;
    private final SetupClientResolver setupClientResolver;

    @GetMapping("/status")
    public SetupStatusResponse status() {
        return SetupProtoMapper.toStatusResponse(setupStatusService.status());
    }

    @PostMapping("/claim")
    public PanelAuthResponse claim(@RequestBody ClaimSetupRequest body, HttpServletRequest request, HttpServletResponse response) {
        String ownerEmail = setupClaimService.claim(body.getCode(), body.getOwner().getEmail(), body.getPublicUrl(),
            setupClientResolver.resolve(request));
        return issueOwnerSession(ownerEmail, request, response);
    }

    @PostMapping("/sign-in")
    public PanelAuthResponse signIn(@RequestBody SetupOwnerSignInRequest body, HttpServletRequest request, HttpServletResponse response) {
        String ownerEmail = setupClaimService.signInOwner(body.getCode(), setupClientResolver.resolve(request));
        return issueOwnerSession(ownerEmail, request, response);
    }

    @GetMapping(PublicUrlProbe.PROBE_PATH + "{nonce}")
    public ResponseEntity<Void> probe(@PathVariable String nonce) {
        return publicUrlProbe.recognizes(nonce) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private PanelAuthResponse issueOwnerSession(String ownerEmail, HttpServletRequest request, HttpServletResponse response) {
        return signInResponder.signIn(instanceContext.instance(), SignInAttempt.of(ownerEmail, AuthMethod.SETUP_CODE),
            AuthResponseMessage.SETUP_COMPLETE, request, response);
    }
}
