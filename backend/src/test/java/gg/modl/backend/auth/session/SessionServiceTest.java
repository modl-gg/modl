package gg.modl.backend.auth.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.AuthConfiguration;
import gg.modl.backend.auth.repository.PanelSession;
import gg.modl.backend.auth.repository.PanelSessionRepository;
import gg.modl.backend.auth.settings.SignInMethod;
import gg.modl.backend.auth.settings.SignInSettings;
import gg.modl.backend.auth.settings.SignInSettingsService;
import gg.modl.backend.auth.signin.SecondFactorPolicy;
import gg.modl.backend.database.DatabaseUnavailableException;
import gg.modl.backend.database.DatabaseUnavailableException.Reason;
import gg.modl.backend.infrastructure.util.DigestUtils;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionServiceTest {
    private static final Server SERVER = TestServers.server("Alpha", "owner@example.com");
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final Duration LIFETIME = Duration.ofSeconds(AuthConfiguration.MIN_SESSION_DURATION_SECONDS);
    private static final String EMAIL = "staff@example.com";
    private static final String PUBLIC_ID = SessionPublicId.of("session-id");

    private final PanelSessionRepository sessionRepository = mock(PanelSessionRepository.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final SecondFactorPolicy secondFactorPolicy = mock(SecondFactorPolicy.class);
    private final SignInSettingsService signInSettings = mock(SignInSettingsService.class);
    private final SessionService sessionService = new SessionService(sessionRepository, secondFactorPolicy, signInSettings,
        new AuthConfiguration(), idGenerator, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void admitEverySessionByDefault() {
        when(secondFactorPolicy.admitsEverywhere(any())).thenReturn(true);
        when(signInSettings.current()).thenReturn(SignInSettings.DEFAULTS);
    }

    @Test
    void authenticateRefusesASessionStillWaitingForItsSecondFactor() {
        when(sessionRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(storedSession(NOW.plus(LIFETIME))));
        when(secondFactorPolicy.admitsEverywhere(any())).thenReturn(false);

        assertFalse(sessionService.authenticate(SERVER, "session-id", "right-key").isPresent());
        assertTrue(sessionService.authenticateAllowingPendingSecondFactor(SERVER, "session-id", "right-key").isPresent());
    }

    @Test
    void whileTwoFactorIsEnforcedOnlyVerifiedSessionsStayActiveForRealtime() {
        when(signInSettings.current()).thenReturn(new SignInSettings(SignInMethod.EMAIL_CODE, true));
        when(sessionRepository.findActivePublicIds(List.of(PUBLIC_ID), NOW, true)).thenReturn(Set.of());

        assertTrue(sessionService.activeSessionIds(SERVER, List.of("session-id")).isEmpty());
        verify(sessionRepository).findActivePublicIds(List.of(PUBLIC_ID), NOW, true);
    }

    @Test
    void createSessionRecordsTheMethodAndTheVerifiedFlag() {
        when(idGenerator.generateToken()).thenReturn("session-key", "panel-session-id");

        IssuedSession issued = sessionService.createSession(SERVER, EMAIL, AuthMethod.PASSKEY, true, null, null);

        ArgumentCaptor<PanelSession> stored = ArgumentCaptor.forClass(PanelSession.class);
        verify(sessionRepository).insert(stored.capture());
        assertEquals(AuthMethod.PASSKEY, stored.getValue().authMethod());
        assertTrue(stored.getValue().secondFactorVerified());
        assertTrue(issued.session().isSecondFactorVerified());
    }

    @Test
    void createSessionDoesNotDeleteSiblingPanelSessionsForEmail() {
        when(idGenerator.generateToken()).thenReturn("session-key", "panel-session-id");

        sessionService.createSession(SERVER, EMAIL, AuthMethod.EMAIL_CODE, false, "127.0.0.1", "JUnit");

        verify(sessionRepository, never()).deleteByEmail(any());
        verify(sessionRepository).insert(any());
    }

    @Test
    void createSessionStoresOnlyDigestsAndReturnsTheSecrets() {
        when(idGenerator.generateToken()).thenReturn("session-key", "panel-session-id");

        IssuedSession issued = sessionService.createSession(SERVER, "Staff@Example.com", AuthMethod.EMAIL_CODE, false, "127.0.0.1", "JUnit");

        ArgumentCaptor<PanelSession> stored = ArgumentCaptor.forClass(PanelSession.class);
        verify(sessionRepository).insert(stored.capture());
        assertEquals(new PanelSession(SessionPublicId.of("panel-session-id"), EMAIL, DigestUtils.sha256Hex("session-key"),
            "127.0.0.1", "JUnit", NOW, NOW.plus(LIFETIME), AuthMethod.EMAIL_CODE, false), stored.getValue());
        assertEquals("session-key", issued.sessionKey());
        assertEquals("panel-session-id", issued.session().getId());
    }

    @Test
    void anAddressThatDoesNotFitNeverFailsTheLogin() {
        when(idGenerator.generateToken()).thenReturn("key-1", "id-1", "key-2", "id-2");

        IssuedSession zoned = sessionService.createSession(SERVER, EMAIL, AuthMethod.EMAIL_CODE, false, "fe80::1ff:fe23:4567:890a%enp0s31f6", null);
        IssuedSession unusable = sessionService.createSession(SERVER, EMAIL, AuthMethod.EMAIL_CODE, false, "x".repeat(46), null);

        assertEquals("fe80::1ff:fe23:4567:890a", zoned.session().getIpAddress());
        assertNull(unusable.session().getIpAddress());
    }

    @Test
    void authenticateAcceptsOnlyTheMatchingSessionKey() {
        when(sessionRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(storedSession(NOW.plus(LIFETIME))));

        assertEquals("session-id", sessionService.authenticate(SERVER, "session-id", "right-key").orElseThrow().getId());
        assertFalse(sessionService.authenticate(SERVER, "session-id", "wrong-key").isPresent());
    }

    @Test
    void aRecentlyRefreshedSessionIsNotWrittenAgain() {
        Instant expiresAt = NOW.plus(LIFETIME).minus(Duration.ofMinutes(9));
        when(sessionRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(storedSession(expiresAt)));

        assertEquals(expiresAt, sessionService.authenticate(SERVER, "session-id", "right-key").orElseThrow().getExpiresAt());
        verify(sessionRepository, never()).refresh(any(), any());
    }

    @Test
    void aDueSessionIsRefreshedToAFullLifetime() {
        Instant expiresAt = NOW.plus(LIFETIME).minus(Duration.ofMinutes(11));
        when(sessionRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(storedSession(expiresAt)));
        when(sessionRepository.refresh(PUBLIC_ID, NOW.plus(LIFETIME))).thenReturn(true);

        assertEquals(NOW.plus(LIFETIME), sessionService.authenticate(SERVER, "session-id", "right-key").orElseThrow().getExpiresAt());
    }

    @Test
    void aDueSessionStaysSignedInAtItsCurrentExpiryWhileWritesArePausedForADatabaseMove() {
        Instant expiresAt = NOW.plus(LIFETIME).minus(Duration.ofMinutes(11));
        when(sessionRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(storedSession(expiresAt)));
        when(sessionRepository.refresh(PUBLIC_ID, NOW.plus(LIFETIME))).thenThrow(new DatabaseUnavailableException(Reason.MOVING));

        assertEquals(expiresAt, sessionService.authenticate(SERVER, "session-id", "right-key").orElseThrow().getExpiresAt());
    }

    @Test
    void anotherUnavailableDatabaseStillFailsTheRefresh() {
        when(sessionRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(storedSession(NOW.plusSeconds(60))));
        when(sessionRepository.refresh(any(), any())).thenThrow(new DatabaseUnavailableException(Reason.SHUTTING_DOWN));

        assertThrows(DatabaseUnavailableException.class, () -> sessionService.authenticate(SERVER, "session-id", "right-key"));
    }

    @Test
    void aWrongKeyNeverRefreshesADueSession() {
        when(sessionRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(storedSession(NOW.plusSeconds(60))));

        assertFalse(sessionService.authenticate(SERVER, "session-id", "wrong-key").isPresent());
        verify(sessionRepository, never()).refresh(any(), any());
    }

    @Test
    void aSessionRevokedWhileItIsBeingRefreshedIsRejected() {
        when(sessionRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(storedSession(NOW.plusSeconds(60))));

        assertFalse(sessionService.authenticate(SERVER, "session-id", "right-key").isPresent());
    }

    @Test
    void onlyTheOwnerRevokesASessionByItsPublicId() {
        when(sessionRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(storedSession(NOW.plus(LIFETIME))));
        when(sessionRepository.deleteByPublicId(PUBLIC_ID)).thenReturn(true);

        assertFalse(sessionService.revokeOwnSession(SERVER, "other@example.com", PUBLIC_ID));
        verify(sessionRepository, never()).deleteByPublicId(any());
        assertTrue(sessionService.revokeOwnSession(SERVER, "Staff@Example.com", PUBLIC_ID));
    }

    @Test
    void sessionToStringRevealsNoIdentifiersOrDigest() {
        String rendered = new AuthSessionData("session-id", EMAIL, NOW, NOW, null, null, "digest-value", AuthMethod.EMAIL_CODE, false).toString();

        assertFalse(rendered.contains("session-id"));
        assertFalse(rendered.contains("digest-value"));
        assertTrue(rendered.contains(EMAIL));
    }

    @Test
    void authenticateWithoutAKeyNeverTouchesTheDatabase() {
        assertFalse(sessionService.authenticate(SERVER, "session-id", null).isPresent());
        assertFalse(sessionService.authenticate(SERVER, "session-id", " ").isPresent());

        verifyNoInteractions(sessionRepository);
    }

    private static PanelSession storedSession(Instant expiresAt) {
        return new PanelSession(PUBLIC_ID, EMAIL, DigestUtils.sha256Hex("right-key"), null, null, NOW, expiresAt, AuthMethod.EMAIL_CODE,
            false);
    }
}
