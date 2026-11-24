package gg.modl.backend.staff.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.infrastructure.util.IpAddresses;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.proto.modl.v1.SyncStaff2faVerification;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StaffTwoFactorService {
    private static final Duration SESSION_DURATION = Duration.ofDays(7);
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(10);

    private final StaffRepository staffRepository;
    private final PublicUrls publicUrls;
    private final OneTimeCodeCodec oneTimeCodeCodec;
    private final IdGenerator idGenerator;
    private final RealtimeEventPublisher realtimeEventPublisher;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public Optional<TwoFactorTokenResult> generateToken(Server server, String minecraftUuid, String ip) {
        String token = idGenerator.generateToken();
        String tokenHash = oneTimeCodeCodec.hash(token);
        String address = IpAddresses.withoutZoneIfStorable(ip);
        if (address == null || address.isBlank()) {
            throw new ValidationException("The connection's IP address is required");
        }
        boolean stored = unitOfWork.write(() -> staffRepository.createTwoFactorToken(
            UuidUtils.normalize(minecraftUuid), tokenHash, address, clock.instant()));
        return stored ? Optional.of(new TwoFactorTokenResult(token, publicUrls.staffVerify(token))) : Optional.empty();
    }

    public Optional<PendingApproval> pendingApproval(Server server, String token, String sessionEmail) {
        return approvableRequest(oneTimeCodeCodec.hash(token), sessionEmail, clock.instant())
            .map(request -> new PendingApproval(request.ip(), request.requestedAt()));
    }

    public boolean verify(Server server, String token, String sessionEmail) {
        String tokenHash = oneTimeCodeCodec.hash(token);
        return unitOfWork.write(() -> activateSession(server, tokenHash, sessionEmail));
    }

    private boolean activateSession(Server server, String tokenHash, String sessionEmail) {
        Instant now = clock.instant();
        Optional<ApprovableRequest> holder = approvableRequest(tokenHash, sessionEmail, now);
        if (holder.isEmpty()) {
            return false;
        }
        ApprovableRequest request = holder.get();
        if (!staffRepository.activateTwoFactorSession(request.staff().requireId(), tokenHash, request.ip(),
            now.plus(SESSION_DURATION))) {
            return false;
        }
        unitOfWork.afterCommit(() -> announceToMinecraft(server, request, tokenHash));
        return true;
    }

    private Optional<ApprovableRequest> approvableRequest(String tokenHash, String sessionEmail, Instant now) {
        return staffRepository.findByTwoFactorTokenHash(tokenHash)
            .filter(staff -> isIdentity(staff, sessionEmail))
            .flatMap(staff -> ApprovableRequest.of(staff, now));
    }

    private void announceToMinecraft(Server server, ApprovableRequest request, String tokenHash) {
        String minecraftUuid = request.staff().getAssignedMinecraftUuid();
        if (minecraftUuid == null || minecraftUuid.isBlank()) {
            return;
        }
        SyncStaff2faVerification approval = SyncStaff2faVerification.newBuilder()
            .setMinecraftUuid(minecraftUuid)
            .setIp(request.ip())
            .build();
        realtimeEventPublisher.pushStaff2fa(server, List.of(approval), tokenHash);
    }

    private static boolean isIdentity(Staff staff, String sessionEmail) {
        String staffEmail = EmailAddressUtil.normalize(staff.getEmail());
        return staffEmail != null && staffEmail.equals(EmailAddressUtil.normalize(sessionEmail));
    }

    private record ApprovableRequest(Staff staff, String ip, long requestedAt) {
        static Optional<ApprovableRequest> of(Staff staff, Instant now) {
            String ip = staff.getTwoFactorTokenIp();
            Long createdAt = staff.getTwoFactorTokenCreatedAt();
            boolean expired = createdAt == null || Instant.ofEpochMilli(createdAt).plus(TOKEN_LIFETIME).isBefore(now);
            if (ip == null || ip.isBlank() || createdAt == null || expired) {
                return Optional.empty();
            }
            return Optional.of(new ApprovableRequest(staff, ip, createdAt));
        }
    }

    public record PendingApproval(String ip, long requestedAt) {
    }

    public record TwoFactorTokenResult(String token, String verifyUrl) {
    }
}
