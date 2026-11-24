package gg.modl.backend.auth.link;

import gg.modl.backend.auth.repository.PanelLoginTokenRepository;
import gg.modl.backend.database.EntityIds;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.service.MinecraftStaffService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PanelLoginLinkService {
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(2);

    private final PanelLoginTokenRepository loginTokenRepository;
    private final MinecraftStaffService minecraftStaffService;
    private final PlayerRepository playerRepository;
    private final PermissionService permissionService;
    private final OneTimeCodeCodec oneTimeCodeCodec;
    private final IdGenerator idGenerator;
    private final PublicUrls publicUrls;
    private final PanelActionAuditor panelActionAuditor;
    private final Clock clock;

    public LoginLinkIssue issue(Server server, String minecraftUuid) {
        if (!MinecraftAccountFormat.isUuid(minecraftUuid)) {
            return new LoginLinkIssue.NotLinked();
        }
        String normalizedUuid = UuidUtils.normalize(minecraftUuid);
        Optional<String> identityEmail = linkedIdentity(server, normalizedUuid);
        if (identityEmail.isEmpty()) {
            return new LoginLinkIssue.NotLinked();
        }
        if (!isOnline(normalizedUuid)) {
            return new LoginLinkIssue.NotOnline();
        }

        String token = idGenerator.generateToken();
        Instant now = clock.instant();
        loginTokenRepository.insert(PanelLoginToken.builder()
            .id(EntityIds.next())
            .tokenHash(oneTimeCodeCodec.hash(token))
            .identityEmail(identityEmail.get())
            .minecraftUuid(normalizedUuid)
            .expiresAt(now.plus(TOKEN_LIFETIME))
            .createdAt(now)
            .build());
        panelActionAuditor.recordStaffAction(server, identityEmail.get(),
            "Requested an in-game panel login link for Minecraft account " + normalizedUuid);
        return new LoginLinkIssue.Issued(publicUrls.loginLink(token), TOKEN_LIFETIME.toSeconds());
    }

    public Optional<String> consume(Server server, String token) {
        Optional<String> identityEmail = loginTokenRepository
            .consumeActive(oneTimeCodeCodec.hash(token), clock.instant())
            .filter(consumed -> isStillLinked(server, consumed))
            .map(PanelLoginToken::getIdentityEmail);
        identityEmail.ifPresent(email ->
            panelActionAuditor.recordStaffAction(server, email, "Signed in to the panel with an in-game login link"));
        return identityEmail;
    }

    private boolean isStillLinked(Server server, PanelLoginToken token) {
        String minecraftUuid = token.getMinecraftUuid();
        return minecraftUuid != null
            && permissionService.isAuthorizedEmail(server, token.getIdentityEmail())
            && minecraftStaffService.isAssignedTo(server, token.getIdentityEmail(), minecraftUuid);
    }

    private Optional<String> linkedIdentity(Server server, String minecraftUuid) {
        return minecraftStaffService.findMinecraftAssignee(server, minecraftUuid)
            .map(Staff::getEmail)
            .filter(email -> permissionService.isAuthorizedEmail(server, email));
    }

    private boolean isOnline(String minecraftUuid) {
        return playerRepository.findByMinecraftUuid(minecraftUuid)
            .map(player -> player.data().isOnline())
            .orElse(false);
    }
}
