package gg.modl.backend.hostedimport.job;

import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.repository.PlayerRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoredPlayerIds {
    private final PlayerRepository playerRepository;

    Map<String, String> byUuid(Collection<String> uuids) {
        Map<String, String> ids = new HashMap<>();
        if (uuids.isEmpty()) {
            return ids;
        }
        for (Player player : playerRepository.findByMinecraftUuids(uuids)) {
            ids.put(player.getMinecraftUuid().toString(), player.getId());
        }
        return ids;
    }
}
