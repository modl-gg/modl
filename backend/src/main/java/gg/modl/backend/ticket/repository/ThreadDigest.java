package gg.modl.backend.ticket.repository;

import gg.modl.backend.ticket.data.TicketReply;
import org.jspecify.annotations.Nullable;

public record ThreadDigest(int visibleReplies, boolean staffResponded, @Nullable TicketReply firstVisibleReply) {
    public static final ThreadDigest EMPTY = new ThreadDigest(0, false, null);
}
