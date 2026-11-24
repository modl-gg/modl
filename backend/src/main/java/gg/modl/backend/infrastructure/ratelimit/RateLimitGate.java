package gg.modl.backend.infrastructure.ratelimit;

import gg.modl.backend.infrastructure.exception.FilterErrorResponse;
import gg.modl.backend.infrastructure.exception.HttpErrorMapping;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.ratelimit.RateLimitConfig.RateLimitTier;
import gg.modl.backend.infrastructure.rest.ClassifiedRoute;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.rest.RouteGroups;
import gg.modl.backend.infrastructure.web.ClientIpResolver;
import gg.modl.backend.instance.MinecraftCredential;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitGate {
    private static final String RATE_LIMIT_REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String RATE_LIMIT_RETRY_AFTER_HEADER = "X-RateLimit-Retry-After";
    private static final String LOWEST_REMAINING_ATTRIBUTE = RateLimitGate.class.getName() + ".lowestRemaining";
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final String RATE_LIMITED_MESSAGE = "Rate limit exceeded";

    private final RateLimitConfig rateLimitConfig;
    private final ClientIpResolver clientIpResolver;
    private final ProtobufErrorResponseWriter protobufErrorResponseWriter;

    public boolean isExempt(HttpServletRequest request) {
        String method = request.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        ClassifiedRoute route = RouteGroups.classify(request);
        if (!route.isApi()) {
            return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        }
        return route.isOperational();
    }

    public boolean admitClient(HttpServletRequest request, HttpServletResponse response) throws IOException {
        RateLimitTier tier = tierOf(request);
        if (!admit(request, response, tier, rateLimitConfig.clientBucket(tier, clientIpResolver.resolveNetwork(request)))) {
            return false;
        }
        if (!tier.isIdentityScoped()) {
            publishRemaining(request, response);
        }
        return true;
    }

    public boolean admitAccount(HttpServletRequest request, HttpServletResponse response) throws IOException {
        RateLimitTier tier = tierOf(request);
        Optional<Bucket> accountBucket = tier.isCredentialScoped()
            ? rateLimitConfig.credentialBucket(tier, credentialOf(request))
            : rateLimitConfig.accountBucket(tier, RequestUtil.getSessionEmail(request), clientIpResolver.resolveNetwork(request));
        if (accountBucket.isEmpty()) {
            return true;
        }
        if (!admit(request, response, tier, accountBucket.get())) {
            return false;
        }
        publishRemaining(request, response);
        return true;
    }

    public boolean admitCredentialAttempt(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ConsumptionProbe probe = failedCredentialBucket(request).tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            reject(request, response, probe);
            log.debug("Too many failed minecraft credentials from one address on path {}", RouteGroups.classify(request).path());
            return false;
        }
        return true;
    }

    public void refundCredentialAttempt(HttpServletRequest request) {
        failedCredentialBucket(request).addTokens(1);
    }

    private Bucket failedCredentialBucket(HttpServletRequest request) {
        return rateLimitConfig.resolveBucket(clientIpResolver.resolveNetwork(request), RateLimitTier.MINECRAFT_FAILED_CREDENTIAL);
    }

    @Nullable
    private static MinecraftCredential credentialOf(HttpServletRequest request) {
        return request.getAttribute(RequestAttribute.MINECRAFT_CREDENTIAL) instanceof MinecraftCredential credential
            ? credential
            : null;
    }

    private RateLimitTier tierOf(HttpServletRequest request) {
        return rateLimitConfig.getTierFor(RouteGroups.classify(request), request.getMethod());
    }

    private boolean admit(HttpServletRequest request, HttpServletResponse response, RateLimitTier tier, Bucket bucket)
        throws IOException {
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            reject(request, response, probe);
            log.debug("Rate limit exceeded on path {} (tier: {})", RouteGroups.classify(request).path(), tier.name());
            return false;
        }
        recordRemaining(request, probe.getRemainingTokens());
        return true;
    }

    private static void recordRemaining(HttpServletRequest request, long remaining) {
        long lowest = lowestRemaining(request).map(recorded -> Math.min(recorded, remaining)).orElse(remaining);
        request.setAttribute(LOWEST_REMAINING_ATTRIBUTE, lowest);
    }

    private static void publishRemaining(HttpServletRequest request, HttpServletResponse response) {
        lowestRemaining(request).ifPresent(lowest -> response.setHeader(RATE_LIMIT_REMAINING_HEADER, String.valueOf(lowest)));
    }

    private static Optional<Long> lowestRemaining(HttpServletRequest request) {
        return request.getAttribute(LOWEST_REMAINING_ATTRIBUTE) instanceof Long recorded ? Optional.of(recorded) : Optional.empty();
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, ConsumptionProbe probe) throws IOException {
        long waitTimeSeconds = retryAfterSeconds(probe.getNanosToWaitForRefill());
        String retryAfter = String.valueOf(waitTimeSeconds);
        response.setHeader(RATE_LIMIT_RETRY_AFTER_HEADER, retryAfter);
        response.setHeader(HttpHeaders.RETRY_AFTER, retryAfter);
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            protobufErrorResponseWriter.write(response, HttpStatus.TOO_MANY_REQUESTS.value(),
                HttpErrorMapping.machineCode(HttpStatus.TOO_MANY_REQUESTS), RATE_LIMITED_MESSAGE);
        } else {
            FilterErrorResponse.writeJsonRetryingAfter(response, HttpStatus.TOO_MANY_REQUESTS, RATE_LIMITED_MESSAGE, waitTimeSeconds);
        }
    }

    private static long retryAfterSeconds(long nanosToWait) {
        return nanosToWait <= 0 ? 0 : Math.max(1, (nanosToWait + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND);
    }
}
