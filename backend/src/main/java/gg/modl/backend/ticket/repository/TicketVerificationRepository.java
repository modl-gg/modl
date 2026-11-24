package gg.modl.backend.ticket.repository;

import java.time.Instant;
import java.util.Optional;

public interface TicketVerificationRepository {
    void lockTicket(String ticketId);

    Optional<VerificationFailures> findFailures(String ticketId, Instant now);

    void countFailure(String ticketId, Instant now, Instant newWindowEnd);

    Optional<Instant> findCodeExpiry(String ticketId);

    void replaceCode(TicketVerificationRecord verification);

    void insertToken(TicketVerificationRecord verification);

    Optional<TicketVerificationRecord> consumeMatchingCode(String ticketId, String codeHash, Instant now);

    boolean incrementFailedAttempts(String ticketId, Instant now);

    boolean existsActiveToken(String ticketId, String tokenHash, Instant now);
}
