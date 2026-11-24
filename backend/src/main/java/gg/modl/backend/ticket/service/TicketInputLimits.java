package gg.modl.backend.ticket.service;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.ticket.repository.ThreadSize;
import org.jspecify.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

public final class TicketInputLimits {
    public static final int MAX_TEXT_BYTES = 256 * 1024;
    public static final int MAX_LINE_BYTES = 4 * 1024;
    public static final int MAX_FORM_BYTES = 512 * 1024;
    public static final int MAX_FORM_ENTRIES = 500;
    public static final int MAX_ATTACHMENTS = 500;
    public static final int MAX_PLAYER_REPLY_CHARACTERS = 10_000;
    public static final int MAX_THREAD_REPLIES_FOR_PLAYERS = 200;
    public static final int MAX_PLAYER_THREAD_CHARACTERS = 1_000_000;
    static final String THREAD_FULL = "TICKET_THREAD_FULL";

    private static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";

    private TicketInputLimits() {
    }

    public static void requireText(String field, @Nullable String value) {
        requireBytes(field, value, MAX_TEXT_BYTES);
    }

    public static void requireLine(String field, @Nullable String value) {
        requireBytes(field, value, MAX_LINE_BYTES);
    }

    public static void requirePlayerReply(@Nullable String content) {
        if (content != null && content.codePointCount(0, content.length()) > MAX_PLAYER_REPLY_CHARACTERS) {
            throw new ValidationException("A reply must be at most " + MAX_PLAYER_REPLY_CHARACTERS + " characters", INVALID_ARGUMENT);
        }
    }

    public static void requireRoomForPlayerReply(ThreadSize thread, int addedCharacters) {
        if (thread.replies() >= MAX_THREAD_REPLIES_FOR_PLAYERS
            || (long) thread.playerCharacters() + addedCharacters > MAX_PLAYER_THREAD_CHARACTERS) {
            throw new ConflictException("This conversation is full; staff will continue it", THREAD_FULL);
        }
    }

    public static void requireForm(String field, @Nullable Map<String, ?> form) {
        if (form == null) {
            return;
        }
        requireCount(field, form.size(), MAX_FORM_ENTRIES);
        requireBytes(field, form.toString(), MAX_FORM_BYTES);
    }

    public static void requireAttachments(@Nullable Collection<?> attachments) {
        if (attachments != null) {
            requireCount("attachments", attachments.size(), MAX_ATTACHMENTS);
        }
    }

    private static void requireBytes(String field, @Nullable String value, int maxBytes) {
        if (value != null && value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new ValidationException(field + " must be at most " + maxBytes + " bytes", INVALID_ARGUMENT);
        }
    }

    private static void requireCount(String field, int count, int maxCount) {
        if (count > maxCount) {
            throw new ValidationException(field + " must have at most " + maxCount + " entries", INVALID_ARGUMENT);
        }
    }
}
