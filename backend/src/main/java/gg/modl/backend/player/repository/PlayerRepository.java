package gg.modl.backend.player.repository;

import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface PlayerRepository {
    Optional<Player> findByMinecraftUuid(String uuid, PlayerParts... parts);

    List<Player> findByMinecraftUuids(Collection<String> uuids, PlayerParts... parts);

    List<Player> findByIds(Collection<String> playerIds, PlayerParts... parts);

    Optional<Player> findByUsernameIgnoreCase(String username, PlayerParts... parts);

    AddressSharers findRecentByIps(Collection<String> ips, @Nullable String excludedPlayerId, int limit);

    List<Player> findOnline(@Nullable String afterPlayerId, int limit, PlayerParts... parts);

    Map<String, String> findOrCreateIdsByMinecraftUuid(Map<String, String> usernameByUuid, Instant now);

    boolean insertIfAbsent(Player player);

    LoginRecord recordLogin(LoginUpdate update);

    void recordUsername(String playerId, String username, Instant seenAt);

    void recordIp(String playerId, String ip, Instant seenAt);

    int updateIpGeoForAddress(String ip, IpGeo geo);

    boolean updateLastServer(String uuid, String serverName);

    void addNote(String playerId, NoteEntry note);

    List<AddressShare> addressShares(String playerId);

    List<LinkCandidate> findLinkCandidates(String playerId, Collection<String> addresses, int limit);

    List<String> findPlayerIdsByAddress(String ip, int limit);

    int link(String playerId, Collection<LinkCandidate> candidates, Instant now);

    int linkUnverified(String playerId, Collection<String> linkedPlayerIds, Instant now);

    void addNotification(String playerId, PlayerNotification notification);

    int acknowledgeNotifications(String playerId, Collection<String> ids);

    Map<String, List<PlayerNotification>> findNotifications(Collection<String> playerIds, Instant now);

    List<Player> search(String term, int limit);

    List<PlayerSummary> findAvailableForStaffAssignment(int limit);

    boolean markOffline(String uuid, Instant now);

    boolean withdrawRefusedLogin(String playerId, Instant loggedInAt);

    Set<String> findOnlineUuids(@Nullable String serverNameOrNull);

    int markOfflineAll(Collection<String> uuids, Instant now);

    Set<String> findEnforcedBanHolders(Collection<String> playerIds, Instant now);

    Map<String, ActiveCounts> countActiveByPlayer(Collection<String> playerIds, Instant now);

    IpPruneBatch pruneIpsUnseenSince(Instant cutoff, Instant now, @Nullable IpHistoryKey after, int batchSize);
}
