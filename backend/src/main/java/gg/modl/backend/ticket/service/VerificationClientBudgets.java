package gg.modl.backend.ticket.service;

import gg.modl.backend.infrastructure.ratelimit.BucketPool;
import gg.modl.backend.infrastructure.ratelimit.BucketSpec;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VerificationClientBudgets {
    static final BucketSpec FAILURES_PER_NETWORK = new BucketSpec("TICKET_VERIFICATION_FAILURES", 5, Duration.ofHours(24));
    static final BucketSpec SENDS_PER_RECORD = new BucketSpec("TICKET_VERIFICATION_SENDS", 10, Duration.ofHours(24));

    private final BucketPool bucketPool;

    public boolean mayTryAnotherCode(String recordId, String clientNetwork) {
        return failures(recordId, clientNetwork).getAvailableTokens() > 0;
    }

    public void chargeWrongCode(String recordId, String clientNetwork) {
        failures(recordId, clientNetwork).tryConsume(1);
    }

    public boolean maySend(String recordId) {
        return sends(recordId).getAvailableTokens() > 0;
    }

    public void chargeSend(String recordId) {
        sends(recordId).tryConsume(1);
    }

    private Bucket sends(String recordId) {
        return bucketPool.resolveBucket(SENDS_PER_RECORD, recordId);
    }

    private Bucket failures(String recordId, String clientNetwork) {
        return bucketPool.resolveBucket(FAILURES_PER_NETWORK, recordId + "|" + clientNetwork);
    }
}
