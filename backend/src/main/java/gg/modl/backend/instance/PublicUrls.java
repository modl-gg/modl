package gg.modl.backend.instance;

import gg.modl.backend.infrastructure.web.HttpOrigin;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PublicUrls {
    private static final String SETUP_PATH = "/setup";

    private final InstanceProperties instanceProperties;
    private final InstanceContext instanceContext;
    private final HttpOrigin localOrigin;
    private final int serverPort;

    public PublicUrls(
        InstanceProperties instanceProperties,
        InstanceContext instanceContext,
        @Value("${server.port:8080}") int serverPort
    ) {
        this.instanceProperties = instanceProperties;
        this.instanceContext = instanceContext;
        this.localOrigin = new HttpOrigin("http", "localhost", serverPort);
        this.serverPort = serverPort;
    }

    public Optional<HttpOrigin> knownOrigin() {
        return configuredOrigin().or(this::storedOrigin);
    }

    public boolean isManagedByConfig() {
        return configuredOrigin().isPresent();
    }

    public HttpOrigin publicOrigin() {
        return knownOrigin().orElse(localOrigin);
    }

    public String origin() {
        return publicOrigin().value();
    }

    public String panel() {
        return origin();
    }

    public String ticket(String ticketId, String accessKey) {
        return origin() + "/ticket/" + encode(ticketId) + "?key=" + encode(accessKey);
    }

    public String staffTicket(String ticketId) {
        return origin() + "/panel/tickets/" + encode(ticketId);
    }

    public String staffVerify(String token) {
        return origin() + "/verify/" + encode(token);
    }

    public String invitation(String token) {
        return origin() + "/accept-invitation?token=" + encode(token);
    }

    public String replay(String replayId) {
        return origin() + replayPath(replayId);
    }

    public String replayPath(String replayId) {
        return "/replay?id=" + encode(replayId);
    }

    public String staffAuditLog() {
        return origin() + "/panel/audit";
    }

    public String playerProfile(String minecraftUuid) {
        return origin() + "/panel/player/" + encode(minecraftUuid);
    }

    public String passwordReset(String token) {
        return origin() + "/reset-password#token=" + encode(token);
    }

    public String loginLink(String token) {
        return origin() + "/auth/link?token=" + encode(token);
    }

    public String setupPage() {
        return origin() + SETUP_PATH;
    }

    public String hostNeutralSetupPage() {
        return "http://<this server's address>:" + serverPort + SETUP_PATH;
    }

    private Optional<HttpOrigin> configuredOrigin() {
        return instanceProperties.configuredPublicUrl().flatMap(HttpOrigin::parseExact);
    }

    private Optional<HttpOrigin> storedOrigin() {
        return Optional.ofNullable(instanceContext.instance().getPublicUrl()).flatMap(HttpOrigin::parseExact);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
