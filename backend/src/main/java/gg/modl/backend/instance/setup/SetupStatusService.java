package gg.modl.backend.instance.setup;

import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.server.data.Server;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SetupStatusService {
    private final InstanceContext instanceContext;
    private final InstanceProperties instanceProperties;

    public SetupStatus status() {
        Server server = instanceContext.instance();
        return new SetupStatus(server.isClaimed(), SetupPhase.of(server), SetupHost.of(instanceProperties));
    }
}
