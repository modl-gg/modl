package gg.modl.backend.iplookup.provider;

import gg.modl.backend.iplookup.model.VpnStatus;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class LookupJson {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private LookupJson() {
    }

    static Optional<JsonNode> object(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            return root.isObject() ? Optional.of(root) : Optional.empty();
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    static String json(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    @Nullable
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isString()) {
            String text = value.stringValue().trim();
            return text.isEmpty() ? null : text;
        }
        return value.isNumber() ? value.asString() : null;
    }

    static Optional<Boolean> flag(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isBoolean()) {
            return Optional.of(value.booleanValue());
        }
        if (value.isNull() || value.isMissingNode()) {
            return Optional.empty();
        }
        if (value.isString()) {
            return Optional.of(!value.stringValue().isEmpty() && !"false".equalsIgnoreCase(value.stringValue()));
        }
        return Optional.empty();
    }

    static VpnStatus anyFlagged(JsonNode node, List<String> fields) {
        boolean allKnown = true;
        for (String field : fields) {
            Optional<Boolean> flagged = flag(node, field);
            if (flagged.orElse(false)) {
                return VpnStatus.YES;
            }
            allKnown &= flagged.isPresent();
        }
        return allKnown ? VpnStatus.NO : VpnStatus.UNKNOWN;
    }

    static boolean refusedStatus(int status) {
        return status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN || status == HTTP_TOO_MANY_REQUESTS;
    }

    static boolean successStatus(int status) {
        return status >= 200 && status < 300;
    }

    @Nullable
    static String asn(@Nullable String number, @Nullable String organisation) {
        String prefixed = number == null ? null : number.regionMatches(true, 0, "AS", 0, 2) ? number : "AS" + number;
        if (prefixed == null) {
            return organisation;
        }
        return organisation == null ? prefixed : prefixed + " " + organisation;
    }
}
