package gg.modl.backend.infrastructure.ratelimit;

import gg.modl.backend.infrastructure.rest.ClassifiedRoute;
import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RESTMappingV2;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

@Component
@RequiredArgsConstructor
public class RateLimitConfig {

    private static final String ACCOUNT_KEY_PREFIX = "account:";
    private static final String CREDENTIAL_KEY_PREFIX = "credential:";

    private final BucketPool bucketPool;

    private static final Set<String> HEAVY_PANEL_WRITE_PATTERNS = Set.of(
        "/staff/invite", "/settings", "/find-linked", "/staff-security"
    );
    private static final Set<String> HEAVY_PANEL_ANY_PATTERNS = Set.of(
        "/dashboard/metrics", "/dashboard/activity",
        "/dashboard/recent-punishments", "/dashboard/recent-tickets",
        "/ticket-subscriptions/assigned-updates", "/ticket-subscriptions/updates"
    );
    private static final Set<String> HEAVY_PUBLIC_POST_PATTERNS = Set.of(
        "/appeals", "/tickets"
    );
    private static final List<PathRule> PREFIX_RULES = List.of(
        new PathRule(RESTMappingV1.MINECRAFT_PANEL + "/", RateLimitTier.MINECRAFT_PANEL_LINK),
        new PathRule(RESTMappingV1.PREFIX_MINECRAFT + "/", RateLimitTier.MINECRAFT_STANDARD),
        new PathRule(RESTMappingV2.PREFIX_MINECRAFT + "/", RateLimitTier.MINECRAFT_STANDARD),
        new PathRule(RESTMappingV3.PREFIX_MINECRAFT + "/", RateLimitTier.MINECRAFT_STANDARD)
    );
    private static final Map<String, RateLimitTier> EXACT_PATH_RULES = Map.ofEntries(
        Map.entry(RESTMappingV1.MINECRAFT_PLAYERS + "/login", RateLimitTier.MINECRAFT_LOGIN),
        Map.entry(RESTMappingV3.PREFIX_MINECRAFT + "/players/login", RateLimitTier.MINECRAFT_LOGIN),
        Map.entry(RESTMappingV1.PUBLIC_SETUP + "/claim", RateLimitTier.SETUP_CLAIM),
        Map.entry(RESTMappingV1.PUBLIC_SETUP + "/sign-in", RateLimitTier.SETUP_CLAIM),
        Map.entry(RESTMappingV1.PANEL_SETUP + "/public-url/check", RateLimitTier.PANEL_HEAVY),
        Map.entry(RESTMappingV1.PANEL_SETTINGS + "/mail/test", RateLimitTier.MAIL_TEST),
        Map.entry(RESTMappingV1.PANEL_SETTINGS + "/ai/models", RateLimitTier.AI_PROBE),
        Map.entry(RESTMappingV1.PANEL_SETTINGS + "/ai/test", RateLimitTier.AI_PROBE),
        Map.entry(RESTMappingV1.PANEL_SETTINGS + "/player-addresses/test", RateLimitTier.IP_LOOKUP_TEST),
        Map.entry(RESTMappingV1.PANEL_SETTINGS_DATABASE + "/test", RateLimitTier.DATABASE_TEST),
        Map.entry(RESTMappingV1.PANEL_SETTINGS_DATABASE + "/move", RateLimitTier.DATABASE_MOVE),
        Map.entry(RESTMappingV1.PANEL_UPDATES + "/check", RateLimitTier.UPDATE_CHECK),
        Map.entry(RESTMappingV1.PANEL_UPDATES + "/install", RateLimitTier.UPDATE_INSTALL),
        Map.entry(RESTMappingV1.PUBLIC_STAFF + "/invitations/accept", RateLimitTier.INVITATION_ACCEPT),
        Map.entry(RESTMappingV1.PUBLIC_STAFF + "/invitations/inspect", RateLimitTier.INVITATION_ACCEPT),
        Map.entry(RESTMappingV1.PANEL_AUTH_PASSWORD_LOGIN, RateLimitTier.AUTH_PASSWORD),
        Map.entry(RESTMappingV1.PANEL_AUTH_PASSWORD_RESET, RateLimitTier.AUTH_PASSWORD),
        Map.entry(RESTMappingV1.PANEL_AUTH_PASSWORD, RateLimitTier.AUTH_PASSWORD),
        Map.entry(RESTMappingV1.PANEL_AUTH_EMAIL, RateLimitTier.AUTH_PASSWORD),
        Map.entry(RESTMappingV1.PANEL_AUTH_PASSWORD_RESET_INSPECT, RateLimitTier.AUTH),
        Map.entry(RESTMappingV1.PANEL_AUTH_SECOND_FACTOR_SIGN_IN, RateLimitTier.AUTH_SECOND_FACTOR),
        Map.entry(RESTMappingV1.PANEL_AUTH_TOTP, RateLimitTier.AUTH_SECOND_FACTOR),
        Map.entry(RESTMappingV1.PANEL_AUTH_TOTP_CONFIRM, RateLimitTier.AUTH_SECOND_FACTOR),
        Map.entry(RESTMappingV1.PANEL_AUTH_TOTP_DISABLE, RateLimitTier.AUTH_SECOND_FACTOR),
        Map.entry(RESTMappingV1.PANEL_AUTH_TOTP_RECOVERY_CODES, RateLimitTier.AUTH_SECOND_FACTOR),
        Map.entry(RESTMappingV1.PANEL_AUTH_WEBAUTHN_REGISTER_OPTIONS, RateLimitTier.AUTH_SECOND_FACTOR),
        Map.entry(RESTMappingV1.PANEL_AUTH_LOGIN_LINK, RateLimitTier.AUTH_LOGIN_LINK),
        Map.entry(RESTMappingV1.PANEL_AUTH_SEND_EMAIL_CODE, RateLimitTier.AUTH_SEND_CODE),
        Map.entry(RESTMappingV1.PANEL_AUTH_EMAIL_CHANGE_SEND_CODE, RateLimitTier.AUTH_SEND_CODE),
        Map.entry(RESTMappingV1.PANEL_ACCOUNT + "/minecraft-link", RateLimitTier.ACCOUNT_MINECRAFT_LINK)
    );
    private static final PathContainer.Options DECODED_PATH = PathContainer.Options.create('/', false);
    private static final List<PathPattern> CREDENTIAL_EXCHANGES = RESTMappingV1.PANEL_AUTH_CREDENTIAL_EXCHANGES.stream()
        .map(PathPatternParser.defaultInstance::parse)
        .toList();

    public Bucket resolveBucket(String clientKey, RateLimitTier tier) {
        return bucketPool.resolveBucket(tier.bucketSpec(), clientKey);
    }

    public Bucket clientBucket(RateLimitTier tier, String clientIp) {
        return resolveBucket(clientIp, tier.clientStageTier());
    }

    public Optional<Bucket> credentialBucket(RateLimitTier tier, @Nullable MinecraftCredential credential) {
        if (!tier.isCredentialScoped() || credential == null) {
            return Optional.empty();
        }
        return Optional.of(resolveBucket(CREDENTIAL_KEY_PREFIX + credential.rateLimitIdentity(), tier));
    }

    public Optional<Bucket> accountBucket(RateLimitTier tier, @Nullable String accountEmail, String clientIp) {
        if (!tier.isAccountScoped()) {
            return Optional.empty();
        }
        return Optional.of(resolveBucket(accountEmail == null ? clientIp : ACCOUNT_KEY_PREFIX + accountEmail, tier));
    }

    public RateLimitTier getTierFor(ClassifiedRoute route, String method) {
        String path = route.path();
        RateLimitTier exact = EXACT_PATH_RULES.get(path);
        if (exact != null) {
            return exact;
        }

        if (route.isChildOf(RESTMappingV1.PANEL_SETUP_IMPORT) || path.equals(RESTMappingV1.PANEL_SETUP_IMPORT)) {
            return importTier(path, method);
        }

        for (PathRule rule : PREFIX_RULES) {
            if (path.startsWith(rule.prefix())) {
                return rule.tier();
            }
        }

        if (route.isStorageObject()) {
            return isWriteMethod(method) ? RateLimitTier.STORAGE_OBJECT_UPLOAD : RateLimitTier.STORAGE_OBJECT_READ;
        }

        if (isCredentialExchange(path)) {
            return RateLimitTier.AUTH;
        }

        if (route.isChildOf(RESTMappingV1.PREFIX_PANEL)) {
            return resolvePanelTier(path, method);
        }

        if (route.isChildOf(RESTMappingV1.PREFIX_PUBLIC)) {
            return resolvePublicTier(path, method);
        }

        return RateLimitTier.PANEL_STANDARD;
    }

    private static RateLimitTier importTier(String path, String method) {
        if ("GET".equalsIgnoreCase(method)) {
            return RateLimitTier.IMPORT_STATUS;
        }
        if ("PUT".equalsIgnoreCase(method) && path.equals(RESTMappingV1.PANEL_SETUP_IMPORT + "/upload")) {
            return RateLimitTier.IMPORT_UPLOAD;
        }
        return RateLimitTier.IMPORT_START;
    }

    private static boolean isCredentialExchange(String path) {
        PathContainer container = PathContainer.parsePath(path, DECODED_PATH);
        return CREDENTIAL_EXCHANGES.stream().anyMatch(pattern -> pattern.matches(container));
    }

    private RateLimitTier resolvePanelTier(String path, String method) {
        if (path.contains("/audit/") || path.contains("/analytics/")) {
            return RateLimitTier.PANEL_AUDIT;
        }
        if (isHeavyOperation(path, method, HEAVY_PANEL_WRITE_PATTERNS, HEAVY_PANEL_ANY_PATTERNS)) {
            return RateLimitTier.PANEL_HEAVY;
        }
        return RateLimitTier.PANEL_STANDARD;
    }

    private RateLimitTier resolvePublicTier(String path, String method) {
        if (path.startsWith(RESTMappingV1.PUBLIC_MEDIA + "/") && isWriteMethod(method)) {
            return RateLimitTier.PUBLIC_MEDIA_UPLOAD;
        }
        if (path.startsWith(RESTMappingV1.PUBLIC_APPEALS) && isWriteMethod(method)
            && (path.contains("/verify") || path.contains("/request-verification"))) {
            return RateLimitTier.PUBLIC_TICKET_VERIFY;
        }
        if (path.startsWith(RESTMappingV1.PUBLIC_TICKETS) && isWriteMethod(method)) {
            if (path.contains("/verify") || path.contains("/request-verification")) {
                return RateLimitTier.PUBLIC_TICKET_VERIFY;
            }
            if (path.equals(RESTMappingV1.PUBLIC_TICKETS) || path.equals(RESTMappingV1.PUBLIC_TICKETS + "/unfinished")) {
                return RateLimitTier.PUBLIC_TICKET_CREATE;
            }
            return RateLimitTier.PUBLIC_TICKET_INTERACT;
        }
        if (isHeavyOperation(path, method, HEAVY_PUBLIC_POST_PATTERNS, Set.of())) {
            return RateLimitTier.PUBLIC_HEAVY;
        }
        return RateLimitTier.PUBLIC_STANDARD;
    }

    private boolean isHeavyOperation(String path, String method, Set<String> writePatterns, Set<String> anyMethodPatterns) {
        if (isWriteMethod(method)) {
            if (path.contains("/tickets") && !path.contains("/replies")) {
                return true;
            }
            for (String pattern : writePatterns) {
                if (path.contains(pattern)) {
                    return true;
                }
            }
        }
        for (String pattern : anyMethodPatterns) {
            if (path.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWriteMethod(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method);
    }

    public enum RateLimitTier {
        MINECRAFT_LOGIN(10000, Duration.ofMinutes(1), RateLimitScope.CREDENTIAL),
        MINECRAFT_STANDARD(6000, Duration.ofMinutes(1), RateLimitScope.CREDENTIAL),
        MINECRAFT_PANEL_LINK(300, Duration.ofMinutes(1), RateLimitScope.CREDENTIAL),
        MINECRAFT_CLIENT_CEILING(30000, Duration.ofMinutes(1)),
        MINECRAFT_FAILED_CREDENTIAL(1000, Duration.ofMinutes(1)),
        PANEL_STANDARD(300, Duration.ofMinutes(1), RateLimitScope.ACCOUNT),
        PANEL_HEAVY(20, Duration.ofMinutes(1), RateLimitScope.ACCOUNT),
        PANEL_AUDIT(200, Duration.ofMinutes(1), RateLimitScope.ACCOUNT),
        PANEL_CLIENT_CEILING(1200, Duration.ofMinutes(1)),
        PUBLIC_STANDARD(60, Duration.ofMinutes(1)),
        PUBLIC_HEAVY(10, Duration.ofMinutes(1)),
        PUBLIC_MEDIA_UPLOAD(30, Duration.ofMinutes(1)),
        PUBLIC_TICKET_CREATE(2, Duration.ofMinutes(1)),
        PUBLIC_TICKET_INTERACT(10, Duration.ofMinutes(1)),
        PUBLIC_TICKET_VERIFY(10, Duration.ofMinutes(1)),
        STORAGE_OBJECT_READ(600, Duration.ofMinutes(1)),
        STORAGE_OBJECT_UPLOAD(30, Duration.ofMinutes(1)),
        SETUP_CLAIM(10, Duration.ofMinutes(1)),
        MAIL_TEST(5, Duration.ofMinutes(1), RateLimitScope.ACCOUNT),
        AI_PROBE(10, Duration.ofMinutes(1), RateLimitScope.ACCOUNT),
        IP_LOOKUP_TEST(5, Duration.ofMinutes(1), RateLimitScope.ACCOUNT),
        DATABASE_TEST(5, Duration.ofMinutes(1), RateLimitScope.ACCOUNT),
        DATABASE_MOVE(5, Duration.ofMinutes(10), RateLimitScope.ACCOUNT),
        UPDATE_CHECK(6, Duration.ofHours(1), RateLimitScope.ACCOUNT),
        UPDATE_INSTALL(3, Duration.ofHours(1), RateLimitScope.ACCOUNT),
        IMPORT_STATUS(120, Duration.ofMinutes(1), RateLimitScope.ACCOUNT),
        IMPORT_UPLOAD(600, Duration.ofMinutes(1), RateLimitScope.ACCOUNT),
        IMPORT_START(10, Duration.ofMinutes(10), RateLimitScope.ACCOUNT),
        INVITATION_ACCEPT(10, Duration.ofMinutes(1)),
        AUTH(20, Duration.ofMinutes(1)),
        AUTH_SEND_CODE(2, Duration.ofMinutes(1)),
        AUTH_LOGIN_LINK(10, Duration.ofMinutes(1)),
        AUTH_PASSWORD(10, Duration.ofMinutes(1)),
        AUTH_SECOND_FACTOR(10, Duration.ofMinutes(1)),
        ACCOUNT_MINECRAFT_LINK(10, Duration.ofMinutes(1));

        private final BucketSpec bucketSpec;
        private final RateLimitScope scope;

        RateLimitTier(int capacity, Duration refillDuration) {
            this(capacity, refillDuration, RateLimitScope.CLIENT);
        }

        RateLimitTier(int capacity, Duration refillDuration, RateLimitScope scope) {
            this.bucketSpec = new BucketSpec(name(), capacity, refillDuration);
            this.scope = scope;
        }

        public boolean isAccountScoped() {
            return scope == RateLimitScope.ACCOUNT;
        }

        public boolean isCredentialScoped() {
            return scope == RateLimitScope.CREDENTIAL;
        }

        public boolean isIdentityScoped() {
            return scope != RateLimitScope.CLIENT;
        }

        RateLimitTier clientStageTier() {
            return switch (scope) {
                case ACCOUNT -> PANEL_CLIENT_CEILING;
                case CREDENTIAL -> MINECRAFT_CLIENT_CEILING;
                case CLIENT -> this;
            };
        }

        public BucketSpec bucketSpec() {
            return bucketSpec;
        }

        public int getCapacity() {
            return bucketSpec.capacity();
        }
    }

    private enum RateLimitScope {
        CLIENT,
        ACCOUNT,
        CREDENTIAL
    }

    private record PathRule(String prefix, RateLimitTier tier) {}
}
