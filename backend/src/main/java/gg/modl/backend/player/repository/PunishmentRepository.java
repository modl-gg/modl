package gg.modl.backend.player.repository;

import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentEvidence;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.data.punishment.PunishmentPhase;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface PunishmentRepository {
    boolean insertIfAbsent(String playerId, Punishment punishment, ResolvedCategory category);

    Optional<PunishmentRecord> findById(String id);

    Optional<PunishmentRecord> lockById(String id);

    void lockAll(Collection<String> idsInLockOrder);

    List<Punishment> findByPlayer(String playerId);

    List<Punishment> findEnforcementRelevant(String playerId, Instant now);

    Map<String, List<Punishment>> findSyncRelevant(Collection<String> playerIds, Instant lastSync, Instant now);

    List<PunishmentRecord> findActiveAltBlockingBansAmongLinked(String playerId, int sharedAccountLimit, Instant now);

    List<Punishment> findLinkedBansOfPlayer(String playerId, Collection<String> parentIds);

    List<String> findLinkedBanIds(Collection<String> parentIds);

    boolean hasQueuedWhileOnline(String playerId);

    List<String> findOnlinePlayersQueuedBehindExpired(Instant expiredFrom, Instant expiredBefore);

    void appendModification(String id, int expectedVersion, PunishmentModification modification, PunishmentPatch patch, List<PunishmentNote> notes);

    boolean markStarted(String id, Instant startedAt);

    boolean promote(String id);

    void patch(String id, int expectedVersion, PunishmentPatch patch);

    void appendNote(String id, PunishmentNote note);

    void appendEvidence(String id, List<PunishmentEvidence> evidence, @Nullable PunishmentNote noteOrNull);

    void attachTicket(String id, String ticketId, PunishmentNote note);

    void replaceTickets(String id, List<String> ticketIds);

    Set<String> findTicketIdsHeldByActiveOrUnstarted(Collection<String> ticketIds, Instant now);

    List<PunishmentRecord> findRecent(Instant issuedAfter, int limit);

    List<PunishmentRecord> findRecentByIssuer(IssuerMatch issuer, Instant issuedAfter, int limit);

    List<PunishmentRecord> findLinkedBans(String parentId);

    List<String> pageIdsByIssuer(IssuerMatch issuer, @Nullable Instant issuedFrom, @Nullable Instant issuedUntil,
                                 @Nullable String afterId, int limit);

    List<String> pageIdsByTypes(Collection<Integer> typeOrdinals, Collection<PunishmentPhase> phases, Instant now,
                                @Nullable String afterId, int limit);

    Optional<PunishmentSearchRow> findSearchRow(String id);

    List<PunishmentSearchRow> findSearchRowsOfPlayer(String playerId);

    List<PunishmentSearchRow> findRecentSearchRows(int limit);

    Map<ResolvedCategory, Long> countByCategory(PunishmentPhase phase, Instant now);

    List<IssuerCount> countByIssuer(Instant issuedFrom);

    long count(PunishmentPhase phase, Instant now);

    Map<Integer, Long> countByType(PunishmentPhase phase, Instant now);

    PunishmentListPage pageListed(PunishmentListQuery query, Instant now, @Nullable PunishmentListCursor after, int limit);
}
