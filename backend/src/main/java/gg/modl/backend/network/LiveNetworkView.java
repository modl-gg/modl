package gg.modl.backend.network;

import java.util.List;
import java.util.Set;

public interface LiveNetworkView {
    List<LiveNetworkSession> sessions();

    Set<String> connectedKeyIds();
}
