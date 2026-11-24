package gg.modl.backend.iplookup.provider;

import gg.modl.backend.iplookup.model.IpLookupAccess;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import java.net.URI;
import org.springframework.stereotype.Component;

@Component
public class KeylessProxycheckClient implements IpLookupClient {
    @Override
    public IpLookupProvider provider() {
        return IpLookupProvider.PROXYCHECK_KEYLESS;
    }

    @Override
    public IpLookupCall call(String ip, IpLookupAccess access) {
        return IpLookupCall.get(URI.create(ProxycheckAnswers.query(ip)));
    }

    @Override
    public IpLookupAnswer read(int status, String body) {
        return ProxycheckAnswers.read(status, body);
    }
}
