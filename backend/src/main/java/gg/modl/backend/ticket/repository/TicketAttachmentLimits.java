package gg.modl.backend.ticket.repository;

import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.infrastructure.exception.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

public final class TicketAttachmentLimits {
    public static final int MAX_ITEM_BYTES = 16 * 1024;
    public static final int MAX_TOTAL_BYTES = 128 * 1024;

    private TicketAttachmentLimits() {
    }

    static void require(List<Object> attachments, JsonColumns json) {
        int total = 0;
        for (Object attachment : attachments) {
            int bytes = json.write(Objects.requireNonNullElse(attachment, "")).getBytes(StandardCharsets.UTF_8).length;
            total += bytes;
            if (bytes > MAX_ITEM_BYTES || total > MAX_TOTAL_BYTES) {
                throw new ValidationException("Attachments are too large");
            }
        }
    }
}
