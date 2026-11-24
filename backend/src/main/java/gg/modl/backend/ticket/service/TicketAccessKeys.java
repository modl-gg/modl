package gg.modl.backend.ticket.service;

import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.ticket.data.Ticket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TicketAccessKeys {
    private final IdGenerator idGenerator;

    public String generate() {
        return idGenerator.generateToken();
    }

    public boolean matches(Ticket ticket, @Nullable String presentedKey) {
        String storedKey = ticket.getPublicAccessKey();
        if (storedKey == null || storedKey.isEmpty() || presentedKey == null || presentedKey.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
            storedKey.getBytes(StandardCharsets.UTF_8),
            presentedKey.getBytes(StandardCharsets.UTF_8)
        );
    }
}
