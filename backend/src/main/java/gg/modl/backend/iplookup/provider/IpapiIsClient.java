package gg.modl.backend.iplookup.provider;

import gg.modl.backend.iplookup.model.IpFacts;
import gg.modl.backend.iplookup.model.IpLookupAccess;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class IpapiIsClient implements IpLookupClient {
    private static final URI ENDPOINT = URI.create("https://api.ipapi.is/");
    private static final List<String> ANONYMISERS = List.of("is_vpn", "is_proxy", "is_tor");

    @Override
    public IpLookupProvider provider() {
        return IpLookupProvider.IPAPI_IS;
    }

    @Override
    public IpLookupCall call(String ip, IpLookupAccess access) {
        return IpLookupCall.postJson(ENDPOINT,
            LookupJson.json(Map.of("q", LookupAddress.require(ip), "key", access.requireKey())));
    }

    @Override
    public IpLookupAnswer read(int status, String body) {
        if (LookupJson.refusedStatus(status)) {
            return new IpLookupAnswer.Refused("ipapi.is refused the lookup");
        }
        Optional<JsonNode> parsed = LookupJson.object(body);
        if (!LookupJson.successStatus(status) || parsed.isEmpty() || parsed.get().has("error")) {
            return new IpLookupAnswer.Failed("ipapi.is answered " + status);
        }
        JsonNode root = parsed.get();
        JsonNode location = root.path("location");
        JsonNode asn = root.path("asn");
        return new IpLookupAnswer.Found(new IpFacts(
            LookupJson.text(location, "country_code"),
            LookupJson.text(location, "state"),
            LookupJson.asn(LookupJson.text(asn, "asn"), LookupJson.text(asn, "org")),
            LookupJson.anyFlagged(root, ANONYMISERS),
            LookupJson.flag(root, "is_datacenter").orElse(false)));
    }
}
