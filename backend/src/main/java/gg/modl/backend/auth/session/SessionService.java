package gg.modl.backend.auth.session;

import gg.modl.backend.auth.AuthConfiguration;
import gg.modl.backend.auth.repository.PanelSession;
import gg.modl.backend.auth.repository.PanelSessionRepository;
import gg.modl.backend.auth.settings.SignInSettingsService;
import gg.modl.backend.auth.signin.SecondFactorPolicy;
import gg.modl.backend.database.DatabaseUnavailableException;
import gg.modl.backend.database.DatabaseUnavailableException.Reason;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.infrastructure.util.IpAddresses;
import gg.modl.backend.server.data.Server;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SessionService {
    private static final Duration REFRESH_SKIP_THRESHOLD = Duration.ofMinutes(10);

    private final PanelSessionRepository sessionRepository;
    private final SecondFactorPolicy secondFactorPolicy;
    private final SignInSettingsService signInSettings;
    private final AuthConfiguration authConfiguration;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public IssuedSession createSession(Server server, String email, AuthMethod method, boolean secondFactorVerified,
                                       @Nullable String ipAddress, @Nullable String userAgent) {
        String identity = EmailAddressUtil.normalize(email);
        if (identity == null) {
            throw new IllegalArgumentException("Session email cannot be empty");
        }

        String sessionKey = idGenerator.generateToken();
        String sessionId = idGenerator.generateToken();
        Instant now = clock.instant();
        PanelSession session = new PanelSession(SessionPublicId.of(sessionId), identity, SessionKeyDigest.digest(sessionKey),
            IpAddresses.withoutZoneIfStorable(ipAddress), userAgent, now, nextExpiry(now), method, secondFactorVerified);
        sessionRepository.insert(session);
        return new IssuedSession(authenticatedSession(sessionId, session, session.expiresAt()), sessionKey);
    }

    public List<PanelSession> findAllSessionsForEmail(Server server, String email) {
        return sessionRepository.findByEmail(email, clock.instant());
    }

    public Set<String> activeSessionIds(Server server, Collection<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Set.of();
        }
        List<String> publicIds = sessionIds.stream().map(SessionPublicId::of).toList();
        boolean verifiedOnly = signInSettings.current().secondFactorRequired();
        Set<String> active = sessionRepository.findActivePublicIds(publicIds, clock.instant(), verifiedOnly);
        return sessionIds.stream()
            .filter(sessionId -> active.contains(SessionPublicId.of(sessionId)))
            .collect(Collectors.toUnmodifiableSet());
    }

    public boolean revokeOwnSession(Server server, String email, String publicId) {
        String identity = EmailAddressUtil.normalize(email);
        boolean owned = sessionRepository.findByPublicId(publicId)
            .filter(session -> session.publicId().equals(publicId) && session.email().equals(identity))
            .isPresent();
        return owned && sessionRepository.deleteByPublicId(publicId);
    }

    public Optional<AuthSessionData> authenticate(Server server, @Nullable String sessionId, @Nullable String sessionKey) {
        return authenticateAllowingPendingSecondFactor(server, sessionId, sessionKey).filter(secondFactorPolicy::admitsEverywhere);
    }

    public Optional<AuthSessionData> authenticateAllowingPendingSecondFactor(Server server, @Nullable String sessionId,
                                                                             @Nullable String sessionKey) {
        if (sessionId == null || sessionId.isBlank() || sessionKey == null || sessionKey.isBlank()) {
            return Optional.empty();
        }
        return sessionRepository.findByPublicId(SessionPublicId.of(sessionId))
            .filter(session -> SessionKeyDigest.matches(session.sessionKeyHash(), sessionKey))
            .flatMap(session -> refreshIfDue(sessionId, session));
    }

    public void invalidateSession(Server server, String sessionId) {
        sessionRepository.deleteByPublicId(SessionPublicId.of(sessionId));
    }

    public void invalidateAllSessionsForEmail(Server server, String email) {
        sessionRepository.deleteByEmail(email);
    }

    private Optional<AuthSessionData> refreshIfDue(String sessionId, PanelSession session) {
        Instant nextExpiry = nextExpiry(clock.instant());
        if (!session.expiresAt().isBefore(nextExpiry.minus(REFRESH_SKIP_THRESHOLD))) {
            return Optional.of(authenticatedSession(sessionId, session, session.expiresAt()));
        }
        try {
            return sessionRepository.refresh(session.publicId(), nextExpiry)
                ? Optional.of(authenticatedSession(sessionId, session, nextExpiry))
                : Optional.empty();
        } catch (DatabaseUnavailableException e) {
            if (e.reason() != Reason.MOVING) {
                throw e;
            }
            return Optional.of(authenticatedSession(sessionId, session, session.expiresAt()));
        }
    }

    private Instant nextExpiry(Instant now) {
        return now.plusSeconds(authConfiguration.getSessionDurationSeconds());
    }

    private static AuthSessionData authenticatedSession(String sessionId, PanelSession session, Instant expiresAt) {
        return new AuthSessionData(sessionId, session.email(), session.createdAt(), expiresAt, session.ipAddress(),
            session.userAgent(), session.sessionKeyHash(), session.authMethod(), session.secondFactorVerified());
    }
}
