package gg.modl.backend.network;

import gg.modl.proto.modl.v1.HostedPlayersEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class HostedPlayerIndex {
    private final Map<String, Set<String>> byCore = new ConcurrentHashMap<>();

    void apply(String coreConnectionId, HostedPlayersEvent event) {
        Set<String> hosted = byCore.computeIfAbsent(coreConnectionId, ignored -> ConcurrentHashMap.newKeySet());
        synchronized (hosted) {
            if (event.getReplace()) {
                hosted.clear();
            }
            event.getLeftUuidsList().forEach(uuid -> hosted.remove(normalized(uuid)));
            for (String uuid : event.getJoinedUuidsList()) {
                if (hosted.size() >= NetworkLimits.HOSTED_PLAYERS_PER_CONNECTION) {
                    break;
                }
                hosted.add(normalized(uuid));
            }
        }
    }

    Optional<String> coreHosting(String playerUuid, List<String> candidateCores) {
        String wanted = normalized(playerUuid);
        List<String> hosting = new ArrayList<>();
        for (String core : candidateCores) {
            Set<String> hosted = byCore.get(core);
            if (hosted != null && hosted.contains(wanted)) {
                hosting.add(core);
            }
        }
        return hosting.size() == 1 ? Optional.of(hosting.get(0)) : Optional.empty();
    }

    void forget(String coreConnectionId) {
        byCore.remove(coreConnectionId);
    }

    private static String normalized(String uuid) {
        return uuid.toLowerCase(Locale.ROOT);
    }
}
