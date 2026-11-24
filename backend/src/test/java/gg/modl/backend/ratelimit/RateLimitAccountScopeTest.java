package gg.modl.backend.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.infrastructure.ratelimit.BucketPool;
import gg.modl.backend.infrastructure.ratelimit.RateLimitConfig;
import gg.modl.backend.infrastructure.ratelimit.RateLimitConfig.RateLimitTier;
import io.github.bucket4j.Bucket;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RateLimitAccountScopeTest {
    private static final String SHARED_IP = "203.0.113.7";
    private static final String FIRST_STAFF = "first@example.com";
    private static final String SECOND_STAFF = "second@example.com";

    private final RateLimitConfig config = new RateLimitConfig(new BucketPool());

    @Test
    void staffAccountsBehindOneAddressHaveSeparateBudgets() {
        Bucket first = accountBucket(RateLimitTier.PANEL_HEAVY, FIRST_STAFF);
        Bucket second = accountBucket(RateLimitTier.PANEL_HEAVY, SECOND_STAFF);

        assertTrue(first.tryConsume(RateLimitTier.PANEL_HEAVY.getCapacity()));

        assertFalse(first.tryConsume(1));
        assertTrue(second.tryConsume(1));
        assertNotSame(first, second);
    }

    @Test
    void accountBudgetsAreKeyedOnTheAccountEmail() {
        assertSame(accountBucket(RateLimitTier.MAIL_TEST, FIRST_STAFF), accountBucket(RateLimitTier.MAIL_TEST, FIRST_STAFF));
    }

    @Test
    void accountScopedTiersShareOnePerAddressCeilingBeforeAuthentication() {
        Bucket ceiling = config.resolveBucket(SHARED_IP, RateLimitTier.PANEL_CLIENT_CEILING);

        assertSame(ceiling, config.clientBucket(RateLimitTier.PANEL_STANDARD, SHARED_IP));
        assertSame(ceiling, config.clientBucket(RateLimitTier.PANEL_AUDIT, SHARED_IP));
        assertSame(ceiling, config.clientBucket(RateLimitTier.MAIL_TEST, SHARED_IP));
    }

    @Test
    void unauthenticatedRequestsOnAccountScopedTiersFallBackToTheClientAddress() {
        Bucket anonymous = config.accountBucket(RateLimitTier.PANEL_STANDARD, null, SHARED_IP).orElseThrow();

        assertSame(config.resolveBucket(SHARED_IP, RateLimitTier.PANEL_STANDARD), anonymous);
        assertNotSame(anonymous, accountBucket(RateLimitTier.PANEL_STANDARD, FIRST_STAFF));
    }

    @Test
    void clientScopedTiersAreLimitedPerAddressOnly() {
        assertSame(config.resolveBucket(SHARED_IP, RateLimitTier.AUTH_SEND_CODE),
            config.clientBucket(RateLimitTier.AUTH_SEND_CODE, SHARED_IP));
        assertEquals(Optional.empty(), config.accountBucket(RateLimitTier.AUTH_SEND_CODE, FIRST_STAFF, SHARED_IP));
        assertFalse(RateLimitTier.PUBLIC_STANDARD.isAccountScoped());
        assertFalse(RateLimitTier.MINECRAFT_STANDARD.isAccountScoped());
        assertFalse(RateLimitTier.INVITATION_ACCEPT.isAccountScoped());
        assertTrue(RateLimitTier.PANEL_HEAVY.isAccountScoped());
    }

    private Bucket accountBucket(RateLimitTier tier, String accountEmail) {
        return config.accountBucket(tier, accountEmail, SHARED_IP).orElseThrow();
    }
}
