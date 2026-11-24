package gg.modl.backend.auth.controller;

import gg.modl.backend.auth.AuthResponseMessage;
import gg.modl.backend.auth.WebAuthnService;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.signin.Reauthentication;
import gg.modl.backend.auth.signin.SensitiveOperation;
import gg.modl.backend.auth.signin.SignInAttempt;
import gg.modl.backend.auth.signin.SignInClientResolver;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.proto.modl.v1.CredentialProof;
import gg.modl.proto.modl.v1.PanelAuthResponse;
import gg.modl.proto.modl.v1.RenameWebAuthnCredentialRequest;
import gg.modl.proto.modl.v1.WebAuthnCredentialMutationResponse;
import gg.modl.proto.modl.v1.WebAuthnCredentialsResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping(RESTMappingV1.PANEL_AUTH_WEBAUTHN)
@RequiredArgsConstructor
public class WebAuthnController {
    private final WebAuthnService webAuthnService;
    private final SignInResponder signInResponder;
    private final SignInClientResolver signInClientResolver;
    private final Reauthentication reauthentication;
    private final PermissionService permissionService;
    private final JsonMapper jsonMapper;

    @PostMapping("/register/options")
    public ResponseEntity<?> registerOptions(HttpServletRequest request, @RequestBody(required = false) CredentialProof proof) {
        AuthSessionData session = RequestUtil.requireSession(request);
        String email = session.getEmail();
        reauthentication.require(session, SensitiveOperation.ADD_CREDENTIAL,
            SignInProtoMapper.fromOptionalProof(proof),
            signInClientResolver.resolve(request));

        Server server = RequestUtil.getRequestServer(request);
        WebAuthnService.StartRegistrationResult result = webAuthnService.startRegistration(server, HttpOrigin.of(request), email);
        Object options = jsonMapper.readValue(result.optionsJson(), Object.class);
        return ResponseEntity.ok(Map.of("challengeId", result.challengeId(), "options", options));
    }

    @PostMapping("/register/verify")
    public ResponseEntity<?> registerVerify(
        HttpServletRequest request,
        @RequestBody @Valid RegisterVerifyRequest body) throws Exception {
        AuthSessionData session = RequestUtil.requireSession(request);

        Server server = RequestUtil.getRequestServer(request);
        webAuthnService.finishRegistration(server, HttpOrigin.of(request), session, body.challengeId(), body.response(), body.name());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/credentials")
    public ResponseEntity<WebAuthnCredentialsResponse> listCredentials(HttpServletRequest request) {
        String email = RequestUtil.requireSessionEmail(request);

        Server server = RequestUtil.getRequestServer(request);
        List<WebAuthnService.CredentialInfo> credentials = webAuthnService.listCredentials(server, email);
        return ResponseEntity.ok(WebAuthnProtoMapper.toCredentialsResponse(credentials));
    }

    @PatchMapping("/credentials/{id}")
    public ResponseEntity<WebAuthnCredentialMutationResponse> renameCredential(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestBody RenameWebAuthnCredentialRequest body) {
        String email = RequestUtil.requireSessionEmail(request);

        Server server = RequestUtil.getRequestServer(request);
        boolean updated = webAuthnService.renameCredential(server, email, id, body.getName());
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(WebAuthnProtoMapper.toMutationResponse(true));
    }

    @DeleteMapping("/credentials/{id}")
    public ResponseEntity<WebAuthnCredentialMutationResponse> deleteCredential(
        HttpServletRequest request,
        @PathVariable String id) {
        String email = RequestUtil.requireSessionEmail(request);

        Server server = RequestUtil.getRequestServer(request);
        boolean deleted = webAuthnService.deleteCredential(server, email, id);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(WebAuthnProtoMapper.toMutationResponse(true));
    }

    @PostMapping("/login/start")
    public ResponseEntity<?> loginStart(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        WebAuthnService.StartAuthenticationResult result = webAuthnService.startDiscoverableAuthentication(server, HttpOrigin.of(request));
        Object options = jsonMapper.readValue(result.optionsJson(), Object.class);
        return ResponseEntity.ok(Map.of("challengeId", result.challengeId(), "options", options));
    }

    @PostMapping("/login/options")
    public ResponseEntity<?> loginOptions(
        HttpServletRequest request,
        @RequestBody @Valid LoginOptionsRequest body) {
        Server server = RequestUtil.getRequestServer(request);

        if (!permissionService.isAuthorizedEmail(server, body.email())) {
            return ResponseEntity.ok(Map.of("hasPasskeys", false));
        }

        boolean hasPasskeys = webAuthnService.checkHasPasskeys(server, body.email());
        if (!hasPasskeys) {
            return ResponseEntity.ok(Map.of("hasPasskeys", false));
        }

        WebAuthnService.StartAuthenticationResult result = webAuthnService.startAuthentication(server, HttpOrigin.of(request), body.email());
        Object options = jsonMapper.readValue(result.optionsJson(), Object.class);
        return ResponseEntity.ok(Map.of(
            "hasPasskeys", true,
            "challengeId", result.challengeId(),
            "options", options
        ));
    }

    @PostMapping("/login/verify")
    public ResponseEntity<PanelAuthResponse> loginVerify(
        HttpServletRequest request,
        HttpServletResponse response,
        @RequestBody @Valid LoginVerifyRequest body) throws Exception {
        Server server = RequestUtil.getRequestServer(request);

        String email = webAuthnService.finishAuthentication(server, HttpOrigin.of(request), body.challengeId(), body.response(),
            candidateEmail -> permissionService.isAuthorizedEmail(server, candidateEmail));

        return ResponseEntity.ok(signInResponder.signIn(server, SignInAttempt.of(email, AuthMethod.PASSKEY),
            AuthResponseMessage.LOGIN_SUCCESS, request, response));
    }

    public record RegisterVerifyRequest(
        @NotBlank @Size(max = 256) String challengeId,
        @NotBlank @Size(max = 10_000) String response,
        @Size(max = 128) String name
    ) {}

    public record LoginOptionsRequest(@Email @NotBlank @Size(max = RequestValidationLimits.EMAIL_MAX_LENGTH) String email) {}

    public record LoginVerifyRequest(
        @NotBlank @Size(max = 256) String challengeId,
        @NotBlank @Size(max = 10_000) String response
    ) {}
}
