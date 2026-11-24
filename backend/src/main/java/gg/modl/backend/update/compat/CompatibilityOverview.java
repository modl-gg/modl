package gg.modl.backend.update.compat;

import gg.modl.backend.update.compat.RefusedServers.RefusedServer;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CompatibilityOverview {
    private final RefusedServers refusedServers;

    public List<RefusedServer> refusedServers() {
        return refusedServers.current();
    }

    public int incompatibleServers() {
        return refusedServers.current().size();
    }
}
