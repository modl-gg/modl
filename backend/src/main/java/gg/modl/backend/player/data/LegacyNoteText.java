package gg.modl.backend.player.data;

public record LegacyNoteText(PlayerNoteKind kind, String text) {
    private static final String HOSTED_WARNING_PREFIX = "WARNING: ";

    public static LegacyNoteText of(String stored) {
        if (stored.startsWith(HOSTED_WARNING_PREFIX)) {
            return new LegacyNoteText(PlayerNoteKind.WARNING, stored.substring(HOSTED_WARNING_PREFIX.length()));
        }
        return new LegacyNoteText(PlayerNoteKind.NOTE, stored);
    }
}
