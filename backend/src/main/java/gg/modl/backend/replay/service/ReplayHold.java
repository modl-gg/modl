package gg.modl.backend.replay.service;

import gg.modl.backend.server.data.Server;
import java.util.List;
import java.util.Set;

public interface ReplayHold {

    Set<String> heldReplayIds(Server server, List<String> replayIds);
}
