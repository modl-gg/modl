package gg.modl.backend.player.data.punishment;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public final class IssuedReason {
    private static final Duration ISSUANCE_WINDOW = Duration.ofMinutes(1);

    private IssuedReason() {
    }

    public static Optional<String> fromLegacyNotes(int typeOrdinal, Instant issued, List<PunishmentNote> notes) {
        if (!PlayerFacingReason.isWrittenForThePlayer(typeOrdinal) || notes.isEmpty()) {
            return Optional.empty();
        }
        PunishmentNote first = notes.getFirst();
        boolean writtenWhenIssued = Duration.between(issued, first.date()).abs().compareTo(ISSUANCE_WINDOW) <= 0;
        if (!writtenWhenIssued || first.text().isBlank() || isGenerated(first.text())) {
            return Optional.empty();
        }
        return Optional.of(first.text());
    }

    public static List<PunishmentNote> withoutReasonCopy(List<PunishmentNote> notes, @Nullable String reason) {
        if (reason == null || notes.isEmpty() || !notes.getFirst().text().equals(reason)) {
            return notes;
        }
        return new ArrayList<>(notes.subList(1, notes.size()));
    }

    private static boolean isGenerated(String note) {
        String text = note.toLowerCase(Locale.ROOT);
        return text.startsWith("issued ")
            || text.equals("pardoned punishment")
            || text.equals("added evidence")
            || text.startsWith("changed duration to ")
            || text.startsWith("opened appeal (#")
            || text.startsWith("enabled ")
            || text.startsWith("disabled ");
    }
}
