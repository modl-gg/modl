package gg.modl.backend.ai.protocol;

import gg.modl.backend.ai.model.AiProtocol;
import gg.modl.backend.ai.model.AiProtocolClient;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AiProtocolClients {
    private final Map<AiProtocol, AiProtocolClient> byProtocol = new EnumMap<>(AiProtocol.class);

    public AiProtocolClients(List<AiProtocolClient> clients) {
        clients.forEach(client -> byProtocol.put(client.protocol(), client));
        for (AiProtocol protocol : AiProtocol.values()) {
            if (!byProtocol.containsKey(protocol)) {
                throw new IllegalStateException("No AI protocol client for " + protocol);
            }
        }
    }

    public AiProtocolClient forProtocol(AiProtocol protocol) {
        AiProtocolClient client = byProtocol.get(protocol);
        if (client == null) {
            throw new IllegalStateException("No AI protocol client for " + protocol);
        }
        return client;
    }
}
