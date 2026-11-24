package gg.modl.backend.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AttestationConveyancePreference;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.PublicKeyCredentialParameters;
import com.yubico.webauthn.data.PublicKeyCredentialType;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import com.yubico.webauthn.data.exception.Base64UrlException;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ExternalServiceException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.UnauthorizedException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.auth.data.WebAuthnChallenge;
import gg.modl.backend.auth.data.WebAuthnCredential;
import gg.modl.backend.auth.repository.PanelSessionRepository;
import gg.modl.backend.auth.repository.WebAuthnChallengeRepository;
import gg.modl.backend.auth.repository.WebAuthnCredentialRepository;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.signin.AccountFactors;
import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.server.data.Server;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebAuthnService {
    private static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(5);
    private static final String DEFAULT_CREDENTIAL_NAME = "Passkey";
    private static final int MAX_CREDENTIAL_NAME_LENGTH = 128;
    private static final List<PublicKeyCredentialParameters> PUBLIC_KEY_ALGORITHMS = List.of(
        PublicKeyCredentialParameters.ES256,
        PublicKeyCredentialParameters.EdDSA,
        PublicKeyCredentialParameters.ES384,
        PublicKeyCredentialParameters.ES512,
        PublicKeyCredentialParameters.Ed448,
        PublicKeyCredentialParameters.RS256,
        PublicKeyCredentialParameters.RS384,
        PublicKeyCredentialParameters.RS512
    );

    private final WebAuthnChallengeRepository challengeRepository;
    private final WebAuthnCredentialRepository credentialRepository;
    private final PublicUrls publicUrls;
    private final AccountFactors accountFactors;
    private final AccountAuthorization accountAuthorization;
    private final PanelSessionRepository sessionRepository;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public StartRegistrationResult startRegistration(Server server, HttpOrigin requestOrigin, String email) {
        RelyingParty rp = buildRelyingParty(server, requestOrigin);

        UserIdentity userIdentity = UserIdentity.builder()
            .name(email)
            .displayName(email)
            .id(userHandle(email))
            .build();

        PublicKeyCredentialCreationOptions options = rp.startRegistration(
            StartRegistrationOptions.builder()
                .user(userIdentity)
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                    .residentKey(ResidentKeyRequirement.PREFERRED)
                    .userVerification(UserVerificationRequirement.REQUIRED)
                    .build())
                .build()
        );

        try {
            String challengeId = storeChallenge(options.toJson(), normalizeEmail(email));
            return new StartRegistrationResult(challengeId, options.toCredentialsCreateJson());
        } catch (JsonProcessingException e) {
            throw new ExternalServiceException("Failed to serialize registration options", e);
        }
    }

    private RelyingParty buildRelyingParty(Server server, HttpOrigin requestOrigin) {
        HttpOrigin publicOrigin = publicUrls.publicOrigin();
        Set<String> origins = new HashSet<>();
        origins.add(publicOrigin.value());
        if (isLocalDevelopmentOrigin(publicOrigin, requestOrigin)) {
            origins.add(requestOrigin.value());
        }

        return RelyingParty.builder()
            .identity(RelyingPartyIdentity.builder()
                .id(publicOrigin.host())
                .name(server.getServerName())
                .build())
            .credentialRepository(new CredentialRepositoryAdapter())
            .origins(origins)
            .allowOriginPort(false)
            .attestationConveyancePreference(AttestationConveyancePreference.NONE)
            .preferredPubkeyParams(PUBLIC_KEY_ALGORITHMS)
            .build();
    }

    static boolean isLocalDevelopmentOrigin(HttpOrigin publicOrigin, HttpOrigin requestOrigin) {
        return publicOrigin.isLoopback() && requestOrigin.isLoopback() && requestOrigin.sameHost(publicOrigin);
    }

    private ByteArray userHandle(String email) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizeEmail(email).getBytes(StandardCharsets.UTF_8));
            return new ByteArray(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String normalizeEmail(String email) {
        return EmailAddressUtil.requireNormalized(email);
    }

    private static String credentialDisplayName(@Nullable String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_CREDENTIAL_NAME;
        }
        String name = requested.trim();
        if (name.codePointCount(0, name.length()) > MAX_CREDENTIAL_NAME_LENGTH) {
            throw new ValidationException("A passkey name can be at most " + MAX_CREDENTIAL_NAME_LENGTH + " characters");
        }
        return name;
    }

    private String storeChallenge(String challengeJson, @Nullable String email) {
        String challengeId = UUID.randomUUID().toString();
        challengeRepository.insert(new WebAuthnChallenge(challengeId, challengeJson, email, clock.instant().plus(CHALLENGE_LIFETIME)));
        return challengeId;
    }

    private WebAuthnChallenge consumeChallenge(String challengeId) {
        return challengeRepository.consumeActive(challengeId, clock.instant())
            .orElseThrow(() -> new ResourceNotFoundException("Challenge not found or expired"));
    }

    public void finishRegistration(Server server, HttpOrigin requestOrigin, AuthSessionData session, String challengeId, String responseJson,
                                   String credentialName) throws Exception {
        String email = session.getEmail();
        String name = credentialDisplayName(credentialName);
        boolean hadSecondFactor = accountFactors.hasSecondFactor(email);
        RelyingParty rp = buildRelyingParty(server, requestOrigin);
        WebAuthnChallenge challenge = consumeChallenge(challengeId);

        String normalizedEmail = normalizeEmail(email);
        if (!normalizedEmail.equals(challenge.getEmail())) {
            throw new ValidationException("Email mismatch");
        }

        PublicKeyCredentialCreationOptions options = PublicKeyCredentialCreationOptions.fromJson(challenge.getChallengeJson());
        PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> pkc =
            PublicKeyCredential.parseRegistrationResponseJson(responseJson);

        RegistrationResult result;
        try {
            result = rp.finishRegistration(
                FinishRegistrationOptions.builder()
                    .request(options)
                    .response(pkc)
                    .build()
            );
        } catch (RegistrationFailedException e) {
            throw new ValidationException("Registration verification failed: " + e.getMessage(), e);
        }

        Instant now = clock.instant();
        WebAuthnCredential credential = new WebAuthnCredential(
            EntityIds.next(),
            normalizedEmail,
            result.getKeyId().getId().getBase64Url(),
            result.getPublicKeyCose().getBytes(),
            result.getSignatureCount(),
            userHandle(normalizedEmail).getBase64Url(),
            name,
            now,
            now);
        unitOfWork.write(() -> {
            accountAuthorization.requireAuthorizedForUpdate(server, normalizedEmail);
            if (!credentialRepository.insertIfAbsent(credential)) {
                throw new ConflictException("This passkey is already registered");
            }
            if (!hadSecondFactor || session.isSecondFactorVerified()) {
                sessionRepository.markSecondFactorVerified(session.publicId());
            }
        });
    }

    public boolean checkHasPasskeys(Server server, String email) {
        return credentialRepository.existsByEmail(email);
    }

    public StartAuthenticationResult startDiscoverableAuthentication(Server server, HttpOrigin requestOrigin) {
        RelyingParty rp = buildRelyingParty(server, requestOrigin);
        AssertionRequest assertionRequest = rp.startAssertion(
            StartAssertionOptions.builder()
                .userVerification(UserVerificationRequirement.REQUIRED)
                .build()
        );

        try {
            String challengeId = storeChallenge(assertionRequest.toJson(), null);
            return new StartAuthenticationResult(challengeId, assertionRequest.toCredentialsGetJson(), true);
        } catch (JsonProcessingException e) {
            throw new ExternalServiceException("Failed to serialize assertion request", e);
        }
    }

    public StartAuthenticationResult startAuthentication(Server server, HttpOrigin requestOrigin, String email) {
        RelyingParty rp = buildRelyingParty(server, requestOrigin);
        AssertionRequest assertionRequest = rp.startAssertion(
            StartAssertionOptions.builder()
                .username(normalizeEmail(email))
                .userVerification(UserVerificationRequirement.REQUIRED)
                .build()
        );

        try {
            String challengeId = storeChallenge(assertionRequest.toJson(), normalizeEmail(email));
            return new StartAuthenticationResult(challengeId, assertionRequest.toCredentialsGetJson(), true);
        } catch (JsonProcessingException e) {
            throw new ExternalServiceException("Failed to serialize assertion request", e);
        }
    }

    public String finishAuthentication(Server server, HttpOrigin requestOrigin, String challengeId, String responseJson, Predicate<String> isAuthorized) throws Exception {
        RelyingParty rp = buildRelyingParty(server, requestOrigin);
        WebAuthnChallenge challenge = consumeChallenge(challengeId);

        AssertionRequest assertionRequest = AssertionRequest.fromJson(challenge.getChallengeJson());
        PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> pkc =
            PublicKeyCredential.parseAssertionResponseJson(responseJson);

        AssertionResult result;
        try {
            result = rp.finishAssertion(
                FinishAssertionOptions.builder()
                    .request(assertionRequest)
                    .response(pkc)
                    .build()
            );
        } catch (AssertionFailedException e) {
            throw new UnauthorizedException("Authentication verification failed: " + e.getMessage(), e);
        }

        if (!result.isSuccess()) {
            throw new UnauthorizedException("Authentication failed");
        }

        if (!result.isSignatureCounterValid()) {
            log.warn("WebAuthn signature counter invalid for credential {}: possible cloned authenticator",
                result.getCredential().getCredentialId().getBase64Url());
            throw new UnauthorizedException("Authentication failed: possible cloned authenticator");
        }

        String email = challenge.getEmail();
        if (email == null || email.isBlank()) {
            ByteArray userHandle = result.getCredential().getUserHandle();
            if (userHandle == null) {
                throw new ResourceNotFoundException("Could not determine user identity");
            }
            WebAuthnCredential cred = credentialRepository.findByUserHandle(userHandle.getBase64Url()).orElse(null);
            if (cred == null) {
                throw new ResourceNotFoundException("Could not determine user identity");
            }
            email = cred.getEmail();
        }

        if (!isAuthorized.test(email)) {
            throw new ValidationException("Not authorized");
        }

        String credentialId = result.getCredential().getCredentialId().getBase64Url();
        boolean updated = credentialRepository.updateUsage(credentialId, result.getSignatureCount(), clock.instant());
        if (!updated) {
            throw new UnauthorizedException("Authentication failed: credential not found");
        }

        return email;
    }

    public List<CredentialInfo> listCredentials(Server server, String email) {
        return credentialRepository.findByEmail(email)
            .stream()
            .map(c -> new CredentialInfo(c.getId(), c.getName(), c.getCreatedAt(), c.getLastUsedAt()))
            .collect(Collectors.toList());
    }

    public boolean renameCredential(Server server, String email, String credentialRowId, String newName) {
        return credentialRepository.renameByIdAndEmail(credentialRowId, email, credentialDisplayName(newName));
    }

    public boolean deleteCredential(Server server, String email, String credentialRowId) {
        return unitOfWork.write(() -> {
            boolean deleted = credentialRepository.deleteByIdAndEmail(credentialRowId, email);
            if (deleted) {
                accountFactors.afterFactorRemoved(email);
            }
            return deleted;
        });
    }

    public record StartRegistrationResult(String challengeId, String optionsJson) {
    }

    public record StartAuthenticationResult(String challengeId, String optionsJson, boolean hasPasskeys) {
    }

    public record CredentialInfo(String id, String name, Instant createdAt, Instant lastUsedAt) {
    }

    private class CredentialRepositoryAdapter implements CredentialRepository {
        @Override
        public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
            return credentialRepository.findByEmail(username)
                .stream()
                .map(c -> {
                    try {
                        return PublicKeyCredentialDescriptor.builder()
                            .id(ByteArray.fromBase64Url(c.getCredentialId()))
                            .type(PublicKeyCredentialType.PUBLIC_KEY)
                            .build();
                    } catch (Base64UrlException e) {
                        log.error("Invalid base64url credential ID for {}", c.getId(), e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        }

        @Override
        public Optional<ByteArray> getUserHandleForUsername(String username) {
            return Optional.of(userHandle(username));
        }

        @Override
        public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
            return credentialRepository.findByUserHandle(userHandle.getBase64Url()).map(WebAuthnCredential::getEmail);
        }

        @Override
        public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
            return credentialRepository.findByCredentialId(credentialId.getBase64Url())
                .filter(cred -> {
                    try {
                        ByteArray storedHandle = ByteArray.fromBase64Url(cred.getUserHandle());
                        return storedHandle.equals(userHandle);
                    } catch (Base64UrlException e) {
                        log.error("Invalid base64url user handle for credential {}", cred.getId(), e);
                        return false;
                    }
                })
                .map(cred -> RegisteredCredential.builder()
                    .credentialId(credentialId)
                    .userHandle(userHandle)
                    .publicKeyCose(new ByteArray(cred.getPublicKeyCose()))
                    .signatureCount(cred.getSignatureCount())
                    .build());
        }

        @Override
        public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
            return credentialRepository.findByCredentialId(credentialId.getBase64Url())
                .stream()
                .map(c -> RegisteredCredential.builder()
                    .credentialId(credentialId)
                    .userHandle(userHandle(c.getEmail()))
                    .publicKeyCose(new ByteArray(c.getPublicKeyCose()))
                    .signatureCount(c.getSignatureCount())
                    .build())
                .collect(Collectors.toSet());
        }
    }
}
