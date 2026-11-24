package gg.modl.backend.auth.link;

import gg.modl.backend.auth.repository.MinecraftLinkCodeRepository;
import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.onetimecode.HumanCode;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.service.MinecraftLinkOutcome;
import gg.modl.backend.staff.service.MinecraftStaffService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MinecraftAccountLinkService {
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    private static final int CODE_LENGTH = 8;
    private static final String LINK_COMMAND_PREFIX = "/modl link ";

    private final MinecraftLinkCodeRepository linkCodeRepository;
    private final MinecraftStaffService minecraftStaffService;
    private final OneTimeCodeCodec oneTimeCodeCodec;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public LinkCodeIssue issueCode(Server server, String identityEmail) {
        String code = HumanCode.generate(CODE_LENGTH);
        Instant now = clock.instant();
        linkCodeRepository.replaceForIdentity(MinecraftLinkCode.builder()
            .id(EntityIds.next())
            .codeHash(oneTimeCodeCodec.hash(code))
            .identityEmail(EmailAddressUtil.requireNormalized(identityEmail))
            .expiresAt(now.plus(CODE_LIFETIME))
            .createdAt(now)
            .build());
        return new LinkCodeIssue(code, CODE_LIFETIME.toSeconds(), LINK_COMMAND_PREFIX + code);
    }

    public AccountLinkResult redeem(Server server, String code, String minecraftUuid, String minecraftUsername) {
        if (!MinecraftAccountFormat.isUuid(minecraftUuid) || !MinecraftAccountFormat.isUsername(minecraftUsername)) {
            throw new ValidationException("A valid Minecraft UUID and username are required");
        }
        if (code == null || code.isBlank()) {
            return AccountLinkResult.INVALID_CODE;
        }
        String codeHash = oneTimeCodeCodec.hash(HumanCode.normalize(code));
        Instant now = clock.instant();
        return unitOfWork.write(() -> linkWithCode(server, codeHash, now, minecraftUuid, minecraftUsername));
    }

    public void unlink(Server server, String identityEmail) {
        minecraftStaffService.unlinkOwnMinecraftAccount(server, identityEmail);
    }

    private AccountLinkResult linkWithCode(Server server, String codeHash, Instant now, String minecraftUuid,
                                           String minecraftUsername) {
        Optional<MinecraftLinkCode> active = linkCodeRepository.findActive(codeHash, now);
        if (active.isEmpty()) {
            return AccountLinkResult.INVALID_CODE;
        }
        if (minecraftStaffService.isAssignedToAnotherIdentity(server, active.get().getIdentityEmail(), minecraftUuid)) {
            return AccountLinkResult.ALREADY_LINKED;
        }
        Optional<MinecraftLinkCode> consumed = linkCodeRepository.consumeActive(codeHash, now);
        if (consumed.isEmpty()) {
            return AccountLinkResult.INVALID_CODE;
        }
        MinecraftLinkOutcome outcome = minecraftStaffService.linkOwnMinecraftAccount(
            server, consumed.get().getIdentityEmail(), minecraftUuid, minecraftUsername);
        return switch (outcome) {
            case LINKED -> AccountLinkResult.LINKED;
            case IDENTITY_NOT_FOUND -> AccountLinkResult.INVALID_CODE;
            case ASSIGNED_TO_OTHER -> AccountLinkResult.ALREADY_LINKED;
        };
    }
}
