package gg.modl.backend.ticket.repository;

import java.time.Instant;

public record VerificationFailures(int count, Instant windowEndsAt) {
}
