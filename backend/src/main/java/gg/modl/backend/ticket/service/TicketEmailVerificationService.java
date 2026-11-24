package gg.modl.backend.ticket.service;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.email.EmailHTMLTemplate;
import gg.modl.backend.email.EmailService;
import gg.modl.backend.email.ValidityPeriod;
import gg.modl.backend.infrastructure.exception.ExternalServiceException;
import gg.modl.backend.infrastructure.exception.TooManyRequestsException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.infrastructure.util.DigestUtils;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.config.TicketEmailVerificationConfiguration;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.repository.TicketVerificationRecord;
import gg.modl.backend.ticket.repository.TicketVerificationRepository;
import gg.modl.backend.ticket.repository.VerificationFailures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TicketEmailVerificationService {
    static final String RESEND_COOLDOWN_CODE = "VERIFICATION_COOLDOWN";
    static final String FAILURE_BUDGET_CODE = "VERIFICATION_LOCKED";
    private static final int CODE_LENGTH = 6;

    private final TicketVerificationRepository ticketVerificationRepository;
    private final EmailService emailService;
    private final TicketEmailVerificationConfiguration verificationConfig;
    private final OneTimeCodeCodec oneTimeCodeCodec;
    private final VerificationClientBudgets clientBudgets;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public String sendVerificationCode(Server server, Ticket ticket) {
        String email = resolveDeliverableEmail(ticket);
        if (email == null) {
            log.warn("Skipping ticket verification email for {} due to a missing or invalid contact email", ticket.getId());
            throw new ValidationException("No valid email associated with this ticket");
        }

        if (!clientBudgets.maySend(ticket.getId())) {
            throw new TooManyRequestsException("Too many codes were sent for this record today; try again tomorrow",
                RESEND_COOLDOWN_CODE, VerificationClientBudgets.SENDS_PER_RECORD.refillDuration());
        }
        String code = oneTimeCodeCodec.generateNumericCode(CODE_LENGTH);
        String codeHash = oneTimeCodeCodec.hash(code);
        long validSeconds = verificationConfig.getCodeExpirySeconds();
        unitOfWork.write(() -> {
            Instant now = clock.instant();
            ticketVerificationRepository.lockTicket(ticket.getId());
            requireFailureBudget(ticket.getId(), now);
            requireOutsideCooldown(ticket.getId(), now, validSeconds);
            ticketVerificationRepository.replaceCode(new TicketVerificationRecord(
                EntityIds.next(), ticket.getId(), null, codeHash, email, 0, now.plusSeconds(validSeconds)));
        });
        clientBudgets.chargeSend(ticket.getId());

        try {
            String serverName = server.getServerName() != null ? server.getServerName() : "Server";
            EmailHTMLTemplate.HTMLEmail emailContent = EmailHTMLTemplate.TICKET_VERIFICATION_CODE.build(
                serverName, code, ValidityPeriod.ofSeconds(validSeconds));
            emailService.send(email, emailContent);
        } catch (Exception e) {
            log.error("Failed to send verification code email for ticket {}", ticket.getId(), e);
            throw new ExternalServiceException("Failed to send verification email", e);
        }

        return EmailAddressUtil.mask(email);
    }

    private Optional<VerificationFailures> spentFailureBudget(String ticketId, Instant now) {
        return ticketVerificationRepository.findFailures(ticketId, now)
            .filter(failures -> failures.count() >= verificationConfig.getFailureLimit());
    }

    private void requireFailureBudget(String ticketId, Instant now) {
        spentFailureBudget(ticketId, now).ifPresent(failures -> {
            throw new TooManyRequestsException("Too many wrong verification codes; try again later",
                FAILURE_BUDGET_CODE, Duration.between(now, failures.windowEndsAt()));
        });
    }

    private void requireOutsideCooldown(String ticketId, Instant now, long validSeconds) {
        ticketVerificationRepository.findCodeExpiry(ticketId)
            .map(expiresAt -> expiresAt.minusSeconds(validSeconds).plusSeconds(verificationConfig.getResendCooldownSeconds()))
            .filter(now::isBefore)
            .ifPresent(resendableAt -> {
                throw new TooManyRequestsException("A code was sent a moment ago; wait before asking for another",
                    RESEND_COOLDOWN_CODE, Duration.between(now, resendableAt));
            });
    }

    @Nullable
    static String resolveDeliverableEmail(Ticket ticket) {
        String email = resolveContactEmail(ticket);
        return email == null ? null : EmailAddressUtil.normalizeIfValid(email);
    }

    @Nullable
    public static String resolveContactEmail(Ticket ticket) {
        if (ticket.getData() == null) {
            return null;
        }
        Object email = ticket.getData().get(TicketDataKeys.CREATOR_EMAIL);
        if (email == null) {
            email = ticket.getData().get(TicketDataKeys.CONTACT_EMAIL);
        }
        if (email == null) {
            return null;
        }
        String value = email.toString();
        return value.isBlank() ? null : value;
    }

    @Nullable
    public String verifyCode(Server server, String ticketId, String code, String clientNetwork) {
        if (!clientBudgets.mayTryAnotherCode(ticketId, clientNetwork)) {
            throw new TooManyRequestsException("Too many wrong verification codes; try again later",
                FAILURE_BUDGET_CODE, VerificationClientBudgets.FAILURES_PER_NETWORK.refillDuration());
        }
        String codeHash = oneTimeCodeCodec.hash(code);
        String token = UUID.randomUUID().toString();
        String tokenHash = DigestUtils.sha256Hex(token);
        long validSeconds = verificationConfig.getAccessExpirySeconds();
        boolean verified = unitOfWork.write(() -> {
            Instant now = clock.instant();
            ticketVerificationRepository.lockTicket(ticketId);
            if (spentFailureBudget(ticketId, now).isPresent()) {
                return false;
            }
            TicketVerificationRecord consumed =
                ticketVerificationRepository.consumeMatchingCode(ticketId, codeHash, now).orElse(null);
            if (consumed == null) {
                if (ticketVerificationRepository.incrementFailedAttempts(ticketId, now)) {
                    ticketVerificationRepository.countFailure(ticketId, now,
                        now.plusSeconds(verificationConfig.getFailureWindowSeconds()));
                }
                return false;
            }
            ticketVerificationRepository.insertToken(new TicketVerificationRecord(
                EntityIds.next(), consumed.ticketId(), tokenHash, null, consumed.email(), 0, now.plusSeconds(validSeconds)));
            return true;
        });
        if (!verified) {
            clientBudgets.chargeWrongCode(ticketId, clientNetwork);
        }
        return verified ? token : null;
    }

    public boolean validateToken(Server server, String ticketId, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return ticketVerificationRepository.existsActiveToken(ticketId, DigestUtils.sha256Hex(token), clock.instant());
    }
}
