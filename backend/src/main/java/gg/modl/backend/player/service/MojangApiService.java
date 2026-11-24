package gg.modl.backend.player.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Slf4j
public class MojangApiService {

    private static final Pattern ACCOUNT_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern ACCOUNT_ID = Pattern.compile("[0-9a-fA-F]{32}");

    private final HttpClient httpClient;
    private static final String PROFILE_BY_NAME_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String PROFILE_BY_UUID_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";

    public MojangApiService() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    public Optional<MojangProfile> lookupByUsername(String username) {
        if (!ACCOUNT_NAME.matcher(username).matches()) {
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(PROFILE_BY_NAME_URL + username))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                return Optional.empty();
            }

            return parseProfile(response.body());
        } catch (Exception e) {
            log.warn("Mojang API lookup by username '{}' failed", username, e);
            return Optional.empty();
        }
    }

    static Optional<MojangProfile> parseProfile(String json) {
        JsonNode root;
        try {
            root = JsonMapper.shared().readTree(json);
        } catch (JacksonException e) {
            return Optional.empty();
        }

        JsonNode nameNode = root.get("name");
        JsonNode idNode = root.get("id");
        if (nameNode == null || !nameNode.isString() || idNode == null || !idNode.isString()) {
            return Optional.empty();
        }

        return Optional.of(new MojangProfile(nameNode.asString(), fromDashlessUuid(idNode.asString())));
    }

    private static UUID fromDashlessUuid(String id) {
        if (id.contains("-")) {
            return UUID.fromString(id);
        }
        String withDashes = id.replaceFirst(
            "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
            "$1-$2-$3-$4-$5"
        );
        return UUID.fromString(withDashes);
    }

    public Optional<MojangProfile> lookupByUuid(String uuid) {
        String dashlessUuid = uuid.replace("-", "");
        if (!ACCOUNT_ID.matcher(dashlessUuid).matches()) {
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(PROFILE_BY_UUID_URL + dashlessUuid))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                return Optional.empty();
            }

            return parseProfile(response.body());
        } catch (Exception e) {
            log.warn("Mojang API lookup by UUID '{}' failed", uuid, e);
            return Optional.empty();
        }
    }

    public record MojangProfile(String name, UUID uuid) {}
}
