package gg.modl.backend.iplookup.provider;

import gg.modl.backend.iplookup.model.IpFacts;
import gg.modl.backend.iplookup.model.VpnStatus;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

final class ProxycheckAnswers {
    private static final String ENDPOINT = "https://proxycheck.io/v2/";
    private static final String QUERY = "?vpn=1&asn=1";
    private static final String STATUS_DENIED = "denied";
    private static final String STATUS_ERROR = "error";
    private static final String HOSTING_TYPE = "Hosting";

    private ProxycheckAnswers() {
    }

    static String query(String ip) {
        return ENDPOINT + LookupAddress.require(ip) + QUERY;
    }

    static IpLookupAnswer read(int status, String body) {
        Optional<JsonNode> parsed = LookupJson.object(body);
        String outcome = parsed.map(root -> LookupJson.text(root, "status")).orElse(null);
        if (LookupJson.refusedStatus(status) || STATUS_DENIED.equals(outcome)) {
            return new IpLookupAnswer.Refused("proxycheck.io refused the lookup");
        }
        if (!LookupJson.successStatus(status) || parsed.isEmpty() || STATUS_ERROR.equals(outcome)) {
            return new IpLookupAnswer.Failed("proxycheck.io answered " + status);
        }
        return addressNode(parsed.get())
            .<IpLookupAnswer>map(node -> new IpLookupAnswer.Found(facts(node)))
            .orElseGet(() -> new IpLookupAnswer.Failed("proxycheck.io returned no result for the address"));
    }

    private static Optional<JsonNode> addressNode(JsonNode root) {
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            if (entry.getValue().isObject() && entry.getValue().has("proxy")) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    private static IpFacts facts(JsonNode node) {
        String proxy = LookupJson.text(node, "proxy");
        VpnStatus vpn = "yes".equalsIgnoreCase(proxy) ? VpnStatus.YES
            : "no".equalsIgnoreCase(proxy) ? VpnStatus.NO : VpnStatus.UNKNOWN;
        return new IpFacts(
            LookupJson.text(node, "isocode"),
            LookupJson.text(node, "region"),
            LookupJson.asn(LookupJson.text(node, "asn"), LookupJson.text(node, "provider")),
            vpn,
            HOSTING_TYPE.equalsIgnoreCase(LookupJson.text(node, "type")));
    }
}
