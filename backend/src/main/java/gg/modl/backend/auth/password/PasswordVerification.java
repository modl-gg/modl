package gg.modl.backend.auth.password;

import gg.modl.backend.auth.repository.StaffCredentialRepository;
import gg.modl.backend.auth.signin.AttemptReservation;
import gg.modl.backend.auth.signin.CredentialAttempts;
import gg.modl.backend.auth.signin.SignInClient;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PasswordVerification {
    private final CredentialAttempts credentialAttempts;
    private final StaffCredentialRepository credentialRepository;
    private final PasswordHasher passwordHasher;

    public String verify(String identity, String normalizedPassword, SignInClient client, BooleanSupplier accountEligible,
                         Supplier<? extends RuntimeException> refusal) {
        AttemptReservation reservation = credentialAttempts.reservePassword(identity, client.identifiedNetwork());
        Optional<String> storedHash;
        boolean accepted;
        try {
            storedHash = credentialRepository.findPasswordHash(identity);
            boolean eligible = accountEligible.getAsBoolean();
            accepted = matches(normalizedPassword, storedHash) && eligible;
        } catch (RuntimeException failure) {
            reservation.refund();
            throw failure;
        }
        if (!accepted) {
            throw refusal.get();
        }
        reservation.succeeded();
        return storedHash.orElseThrow();
    }

    private boolean matches(String normalizedPassword, Optional<String> storedHash) {
        if (storedHash.isEmpty()) {
            passwordHasher.burnForUnknownAccount(normalizedPassword);
            return false;
        }
        return passwordHasher.matches(normalizedPassword, storedHash.get());
    }
}
