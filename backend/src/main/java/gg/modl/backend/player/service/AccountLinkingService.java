package gg.modl.backend.player.service;

import gg.modl.address.AddressScope;
import gg.modl.backend.iplookup.model.VpnStatus;
import gg.modl.backend.iplookup.service.AddressForwardingMonitor;
import gg.modl.backend.iplookup.settings.PlayerAddressSettingsStore;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.dto.response.LinkedAccountResponse;
import gg.modl.backend.player.repository.ActiveCounts;
import gg.modl.backend.player.repository.AddressShare;
import gg.modl.backend.player.repository.LinkCandidate;
import gg.modl.backend.player.repository.PlayerParts;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.server.data.Server;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccountLinkingService {
    static final int MAX_LINKS_PER_RUN = 1000;
    static final int MAX_LISTED_LINKS = 1000;

    private static final Comparator<Player> MOST_RECENTLY_SEEN_FIRST = Comparator
        .comparing((Player linked) -> linked.data().lastLogin(), Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
        .thenComparing(Player::getId);

    private final PlayerRepository playerRepository;
    private final PlayerAddressSettingsStore addressSettings;
    private final AddressForwardingMonitor forwardingMonitor;
    private final Clock clock;

    public List<LinkedAccountResponse> getLinkedAccounts(Server server, UUID playerUuid) {
        Player player = playerRepository.findByMinecraftUuid(playerUuid.toString(), PlayerParts.LINKS).orElse(null);
        if (player == null) {
            return List.of();
        }
        List<String> linkedUuids = player.data().linkedAccountUuids().stream().limit(MAX_LISTED_LINKS).toList();
        if (linkedUuids.isEmpty()) {
            return List.of();
        }
        List<Player> linkedPlayers = playerRepository.findByMinecraftUuids(linkedUuids).stream()
            .sorted(MOST_RECENTLY_SEEN_FIRST)
            .toList();
        Map<String, ActiveCounts> active = playerRepository.countActiveByPlayer(
            linkedPlayers.stream().map(Player::getId).toList(), clock.instant());
        return linkedPlayers.stream()
            .map(linked -> toResponse(linked, active.getOrDefault(linked.getId(), ActiveCounts.NONE)))
            .toList();
    }

    public LinkingResult findAndLinkAccounts(Server server, UUID playerUuid) {
        Player player = playerRepository.findByMinecraftUuid(playerUuid.toString()).orElse(null);
        if (player == null) {
            return new LinkingResult(false, "Player not found", 0);
        }
        return new LinkingResult(true, "Linking complete", linkNewMatches(player.getId(), clock.instant()));
    }

    public int linkNewMatches(String playerId, Instant now) {
        List<String> usable = usableAddresses(playerRepository.addressShares(playerId));
        if (usable.isEmpty()) {
            return 0;
        }
        List<LinkCandidate> candidates = playerRepository.findLinkCandidates(playerId, usable, MAX_LINKS_PER_RUN);
        return candidates.isEmpty() ? 0 : playerRepository.link(playerId, candidates, now);
    }

    public void linkAddressSharers(String ip, Instant now) {
        int limit = addressSettings.current().effectiveSharedAccountLimit();
        List<String> sharers = playerRepository.findPlayerIdsByAddress(ip, limit + 1);
        if (sharers.size() < 2 || sharers.size() > limit) {
            return;
        }
        sharers.forEach(playerId -> linkNewMatches(playerId, now));
    }

    private List<String> usableAddresses(List<AddressShare> shares) {
        int limit = addressSettings.current().effectiveSharedAccountLimit();
        List<String> usable = new ArrayList<>();
        boolean paused = false;
        for (AddressShare share : shares) {
            if (!AddressScope.isPublicLiteral(share.ip()) || share.vpn() == VpnStatus.YES) {
                continue;
            }
            if (share.accounts() > limit) {
                paused = true;
                continue;
            }
            usable.add(share.ip());
        }
        if (paused) {
            forwardingMonitor.sharedAddressPaused();
        }
        return usable;
    }

    private static LinkedAccountResponse toResponse(Player player, ActiveCounts active) {
        return new LinkedAccountResponse(
            player.getMinecraftUuid().toString(),
            PlayerDataUtils.extractLatestUsername(player.getUsernames()),
            Math.toIntExact(active.bans()),
            Math.toIntExact(active.mutes()),
            player.data().lastLinkedUpdate());
    }

    public record LinkingResult(boolean success, String message, int linkedAccountsFound) {
    }
}
