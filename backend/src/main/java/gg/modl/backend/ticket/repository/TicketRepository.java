package gg.modl.backend.ticket.repository;

import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketNote;
import gg.modl.backend.ticket.data.TicketReply;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface TicketRepository {
    boolean insertIfAbsent(Ticket ticket);

    Optional<Ticket> findById(String id);

    List<ListedTicket> findListedByIds(Collection<String> ids);

    List<TicketSummary> findSummaries(Collection<String> ids);

    TicketSearchPage search(TicketSearchFilter filter, TicketSortOption sort, int page, int limit);

    TicketCounts count(TicketSearchFilter filter);

    List<Ticket> findByPlayer(String playerUuid);

    List<Ticket> findByTag(String tag);

    List<Ticket> findReportedPlayerTickets(String reportedPlayerUuid, int limit);

    List<ListedTicket> findMinecraftTickets(@Nullable String status, @Nullable String type, int limit);

    List<ListedTicket> findReports(@Nullable String status, @Nullable String playerUuid, int limit, boolean sortByCreatedDesc);

    List<Ticket> findRecentByCreator(String creatorUuid, int limit);

    List<Ticket> findPlayerTicketsWithReplayUrl(String playerUuid, int limit);

    List<Ticket> findAppealsByPunishmentId(String punishmentId);

    List<TicketSummary> findAppealSummariesByPunishmentId(String punishmentId);

    List<Ticket> findCreatedAfterExcludingUnfinished(Instant after, int limit);

    List<TicketSummary> findRecentActiveWithRepliesByIds(Collection<String> ids, int limit);

    List<TicketSummary> findRecentAssignedWithReplies(String assignee, int limit);

    List<ReplyStamp> findReplyStamps(Collection<String> ticketIds, Instant after);

    Map<String, TicketReply> findReplies(Map<String, Integer> seqByTicketId);

    void appendReply(String id, TicketReply reply, Instant now);

    void appendNote(String id, TicketNote note, Instant now);

    void reassignReplies(String id, Collection<String> replyIds, String authorName, @Nullable String creatorIdentifier);

    void addTags(String id, Collection<String> tags, Instant now);

    void removeTags(String id, Collection<String> tags, Instant now);

    void replaceAssignees(String id, Collection<String> assignees, Instant now);

    void updateHeader(String id, int expectedVersion, TicketHeaderPatch patch);

    long clearReplayReferences(Collection<String> replayIds);

    Map<String, String> findReplayIdsByTicketId(Collection<String> replayIds);

    Set<String> findReplayIdsReferencedByUnresolvedTicket(Collection<String> replayIds);

    Map<String, TicketReply> firstReplies(Collection<String> ticketIds);


    Optional<ThreadSize> findThreadSize(String id);

    int storedCharacters(TicketReply reply);

    int closeIfUnlocked(Collection<String> ids, Instant now);
}
