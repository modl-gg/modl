package gg.modl.backend.iplookup.provider;

import gg.modl.backend.iplookup.model.IpFacts;
import gg.modl.backend.iplookup.model.IpLookupAccess;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import gg.modl.backend.iplookup.model.VpnStatus;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class IphubClient implements IpLookupClient {
    private static final String ENDPOINT = "https://v2.api.iphub.info/ip/";
    private static final String KEY_HEADER = "X-Key";
    private static final int RESIDENTIAL = 0;
    private static final int NON_RESIDENTIAL = 1;

    @Override
    public IpLookupProvider provider() {
        return IpLookupProvider.IPHUB;
    }

    @Override
    public IpLookupCall call(String ip, IpLookupAccess access) {
        return IpLookupCall.get(URI.create(ENDPOINT + LookupAddress.require(ip)), Map.of(KEY_HEADER, access.requireKey()));
    }

    @Override
    public IpLookupAnswer read(int status, String body) {
        if (LookupJson.refusedStatus(status)) {
            return new IpLookupAnswer.Refused("IPHub refused the lookup");
        }
        Optional<JsonNode> parsed = LookupJson.object(body);
        if (!LookupJson.successStatus(status) || parsed.isEmpty() || !parsed.get().path("block").isNumber()) {
            return new IpLookupAnswer.Failed("IPHub answered " + status);
        }
        JsonNode root = parsed.get();
        int block = root.path("block").intValue();
        VpnStatus vpn = block == RESIDENTIAL ? VpnStatus.NO : block == NON_RESIDENTIAL ? VpnStatus.YES : VpnStatus.UNKNOWN;
        return new IpLookupAnswer.Found(new IpFacts(
            LookupJson.text(root, "countryCode"),
            null,
            LookupJson.asn(LookupJson.text(root, "asn"), LookupJson.text(root, "isp")),
            vpn,
            false));
    }
}
