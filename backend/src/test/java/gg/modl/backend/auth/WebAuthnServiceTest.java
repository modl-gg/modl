package gg.modl.backend.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.data.WebAuthnChallenge;
import gg.modl.backend.auth.repository.PanelSessionRepository;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.signin.AccountFactors;
import gg.modl.backend.auth.repository.WebAuthnChallengeRepository;
import gg.modl.backend.auth.repository.WebAuthnCredentialRepository;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WebAuthnServiceTest {
    private static final HttpOrigin PANEL_ORIGIN = new HttpOrigin("https", "panel.example.com", -1);

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private WebAuthnChallengeRepository challengeRepository;
    private WebAuthnCredentialRepository credentialRepository;
    private WebAuthnService webAuthnService;

    @BeforeEach
    void setUp() {
        challengeRepository = mock(WebAuthnChallengeRepository.class);
        credentialRepository = mock(WebAuthnCredentialRepository.class);
        PublicUrls publicUrls = mock(PublicUrls.class);
        when(publicUrls.publicOrigin()).thenReturn(PANEL_ORIGIN);
        webAuthnService = new WebAuthnService(challengeRepository, credentialRepository, publicUrls, mock(AccountFactors.class), mock(AccountAuthorization.class),
            mock(PanelSessionRepository.class), new ImmediateUnitOfWork(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void registrationOptionsRequireUserVerification() {
        WebAuthnService.StartRegistrationResult result =
            webAuthnService.startRegistration(server(), PANEL_ORIGIN, "admin@example.com");

        assertTrue(result.optionsJson().contains("\"userVerification\":\"required\""));
    }

    @Test
    void registrationOptionsUseThePublicUrlHostAsRelyingPartyId() {
        WebAuthnService.StartRegistrationResult result =
            webAuthnService.startRegistration(server(), PANEL_ORIGIN, "admin@example.com");

        assertTrue(result.optionsJson().contains("\"id\":\"panel.example.com\""));
    }

    @Test
    void discoverableAuthenticationOptionsRequireUserVerification() {
        WebAuthnService.StartAuthenticationResult result =
            webAuthnService.startDiscoverableAuthentication(server(), PANEL_ORIGIN);

        assertTrue(result.optionsJson().contains("\"userVerification\":\"required\""));
    }

    @Test
    void theStoredChallengeIsBoundToTheNormalizedEmailForFiveMinutes() {
        WebAuthnService.StartRegistrationResult result =
            webAuthnService.startRegistration(server(), PANEL_ORIGIN, "Admin@Example.com");

        WebAuthnChallenge stored = storedChallenge();
        assertEquals(result.challengeId(), stored.getId());
        assertEquals("admin@example.com", stored.getEmail());
        assertEquals(NOW.plusSeconds(300), stored.getExpiresAt());
    }

    @Test
    void aDiscoverableChallengeNamesNoAccount() {
        webAuthnService.startDiscoverableAuthentication(server(), PANEL_ORIGIN);

        assertNull(storedChallenge().getEmail());
    }

    @Test
    void aRenameIsTrimmedAndBlankNamesFallBackToTheDefault() {
        webAuthnService.renameCredential(server(), "admin@example.com", "id", " Laptop ");
        webAuthnService.renameCredential(server(), "admin@example.com", "id", "  ");

        verify(credentialRepository).renameByIdAndEmail("id", "admin@example.com", "Laptop");
        verify(credentialRepository).renameByIdAndEmail("id", "admin@example.com", "Passkey");
    }

    @Test
    void anOverlongNameIsRejectedBeforeAnythingIsWrittenOrConsumed() {
        String overlong = "n".repeat(129);

        assertThrows(ValidationException.class,
            () -> webAuthnService.renameCredential(server(), "admin@example.com", "id", overlong));
        assertThrows(ValidationException.class,
            () -> webAuthnService.finishRegistration(server(), PANEL_ORIGIN,
                new AuthSessionData("id", "admin@example.com", NOW, NOW, null, null, "digest", AuthMethod.EMAIL_CODE, false),
                "challenge", "{}", overlong));
        verifyNoInteractions(challengeRepository, credentialRepository);
    }

    private WebAuthnChallenge storedChallenge() {
        ArgumentCaptor<WebAuthnChallenge> stored = ArgumentCaptor.forClass(WebAuthnChallenge.class);
        verify(challengeRepository).insert(stored.capture());
        return stored.getValue();
    }

    private static Server server() {
        return TestServers.server("server", "admin@example.com");
    }

    @Test
    void passkeysAreAcceptedOnlyFromThePublicOriginOrItsLocalDevelopmentTwin() {
        HttpOrigin otherPort = new HttpOrigin("https", "panel.example.com", 8443);
        HttpOrigin localPanel = new HttpOrigin("http", "localhost", 18080);
        HttpOrigin localVite = new HttpOrigin("http", "localhost", 3000);

        assertFalse(WebAuthnService.isLocalDevelopmentOrigin(PANEL_ORIGIN, otherPort));
        assertTrue(WebAuthnService.isLocalDevelopmentOrigin(localPanel, localVite));
        assertFalse(WebAuthnService.isLocalDevelopmentOrigin(localPanel, new HttpOrigin("http", "evil.example", 3000)));
    }
}
