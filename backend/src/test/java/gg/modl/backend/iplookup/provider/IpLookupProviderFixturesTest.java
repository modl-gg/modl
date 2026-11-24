package gg.modl.backend.iplookup.provider;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.iplookup.model.IpFacts;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import gg.modl.backend.iplookup.model.VpnStatus;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class IpLookupProviderFixturesTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Map<IpLookupProvider, IpLookupClient> CLIENTS = Map.of(
        IpLookupProvider.PROXYCHECK, new ProxycheckClient(),
        IpLookupProvider.VPNAPI, new VpnapiClient(),
        IpLookupProvider.IPHUB, new IphubClient(),
        IpLookupProvider.IPAPI_IS, new IpapiIsClient(),
        IpLookupProvider.PROXYCHECK_KEYLESS, new KeylessProxycheckClient());

    static Stream<Arguments> fixtures() throws IOException {
        List<Arguments> cases = new ArrayList<>();
        for (IpLookupProvider provider : IpLookupProvider.values()) {
            JsonNode fixture = read(provider);
            assertThat(fixture.path("source").asString()).startsWith("https://");
            for (JsonNode example : fixture.path("cases").values()) {
                cases.add(Arguments.of(provider, example.path("name").asString(), example));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("fixtures")
    void everyDocumentedResponseIsReadAsTheFixtureExpects(IpLookupProvider provider, String name, JsonNode example) {
        IpLookupAnswer answer = CLIENTS.get(provider).read(example.path("status").intValue(),
            MAPPER.writeValueAsString(example.path("body")));

        switch (example.path("outcome").asString()) {
            case "FOUND" -> assertThat(answer).isEqualTo(new IpLookupAnswer.Found(expected(example.path("expected"))));
            case "REFUSED" -> assertThat(answer).isInstanceOf(IpLookupAnswer.Refused.class);
            case "FAILED" -> assertThat(answer).isInstanceOf(IpLookupAnswer.Failed.class);
            default -> throw new IllegalArgumentException("Unknown outcome in fixture " + name);
        }
    }

    private static IpFacts expected(JsonNode expected) {
        return new IpFacts(text(expected, "country"), text(expected, "region"), text(expected, "asn"),
            VpnStatus.valueOf(expected.path("vpn").asString()), expected.path("hosting").booleanValue());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asString();
    }

    private static JsonNode read(IpLookupProvider provider) throws IOException {
        try (InputStream input = IpLookupProviderFixturesTest.class.getResourceAsStream("/iplookup/" + provider.id() + ".json")) {
            assertThat(input).as("fixture for " + provider.id()).isNotNull();
            return MAPPER.readTree(input);
        }
    }
}
