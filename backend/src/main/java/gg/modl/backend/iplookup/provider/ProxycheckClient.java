package gg.modl.backend.iplookup.provider;

import gg.modl.backend.iplookup.model.IpLookupAccess;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class ProxycheckClient implements IpLookupClient {
    @Override
    public IpLookupProvider provider() {
        return IpLookupProvider.PROXYCHECK;
    }

    @Override
    public IpLookupCall call(String ip, IpLookupAccess access) {
        return IpLookupCall.get(URI.create(ProxycheckAnswers.query(ip)
            + "&key=" + URLEncoder.encode(access.requireKey(), StandardCharsets.UTF_8)));
    }

    @Override
    public IpLookupAnswer read(int status, String body) {
        return ProxycheckAnswers.read(status, body);
    }
}
