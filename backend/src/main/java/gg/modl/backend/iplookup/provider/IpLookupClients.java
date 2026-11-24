package gg.modl.backend.iplookup.provider;

import gg.modl.backend.iplookup.model.IpLookupProvider;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class IpLookupClients {
    private final Map<IpLookupProvider, IpLookupClient> byProvider = new EnumMap<>(IpLookupProvider.class);

    public IpLookupClients(List<IpLookupClient> clients) {
        clients.forEach(client -> byProvider.put(client.provider(), client));
    }

    public Optional<IpLookupClient> of(IpLookupProvider provider) {
        return Optional.ofNullable(byProvider.get(provider));
    }
}
