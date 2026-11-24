package gg.modl.backend.iplookup.provider;

import gg.modl.backend.iplookup.model.IpFacts;
import gg.modl.backend.iplookup.model.IpLookupAccess;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class VpnapiClient implements IpLookupClient {
    private static final String ENDPOINT = "https://vpnapi.io/api/";
    private static final List<String> ANONYMISERS = List.of("vpn", "proxy", "tor", "relay");

    @Override
    public IpLookupProvider provider() {
        return IpLookupProvider.VPNAPI;
    }

    @Override
    public IpLookupCall call(String ip, IpLookupAccess access) {
        return IpLookupCall.get(URI.create(ENDPOINT + LookupAddress.require(ip)
            + "?key=" + URLEncoder.encode(access.requireKey(), StandardCharsets.UTF_8)));
    }

    @Override
    public IpLookupAnswer read(int status, String body) {
        if (LookupJson.refusedStatus(status)) {
            return new IpLookupAnswer.Refused("vpnapi.io refused the lookup");
        }
        Optional<JsonNode> parsed = LookupJson.object(body);
        if (!LookupJson.successStatus(status) || parsed.isEmpty() || !parsed.get().path("security").isObject()) {
            return new IpLookupAnswer.Failed("vpnapi.io answered " + status);
        }
        JsonNode root = parsed.get();
        JsonNode location = root.path("location");
        JsonNode network = root.path("network");
        return new IpLookupAnswer.Found(new IpFacts(
            LookupJson.text(location, "country_code"),
            LookupJson.text(location, "region"),
            LookupJson.asn(LookupJson.text(network, "autonomous_system_number"),
                LookupJson.text(network, "autonomous_system_organization")),
            LookupJson.anyFlagged(root.path("security"), ANONYMISERS),
            false));
    }
}
