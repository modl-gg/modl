package gg.modl.backend.instance.setup;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.onetimecode.HumanCode;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.repository.InstanceSecretsRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SetupCodeService {
    static final int CODE_LENGTH = 8;
    static final int LOCK_THRESHOLD = 50;
    static final Duration CODE_LIFETIME = Duration.ofHours(1);

    private final InstanceSecretsRepository instanceSecretsRepository;
    private final UnitOfWork unitOfWork;
    private final InstanceContext instanceContext;
    private final OneTimeCodeCodec oneTimeCodeCodec;
    private final SetupCodeAttempts attempts;
    private final SetupCodeAnnouncer announcer;
    private final SetupCodeFile setupCodeFile;
    private final Clock clock;

    public boolean issueIfNeeded() {
        SetupPhase phase = currentPhase();
        if (!phase.needsCode()) {
            return false;
        }
        String code = HumanCode.generate(CODE_LENGTH);
        IssuedSetupCode issued = issuedCode(code, clock.instant());
        unitOfWork.write(() -> instanceSecretsRepository.storeSetupCode(issued));
        announceIssued(phase, code, issued);
        return true;
    }

    public SetupCodeVerdict consume(String code, SetupClient client, Runnable onMatch) {
        if (!attempts.tryAcquire(client)) {
            return SetupCodeVerdict.LOCKED;
        }
        String codeHash = oneTimeCodeCodec.hash(HumanCode.normalize(code));
        SetupCodeVerdict verdict = unitOfWork.write(() -> consumeOrRecordFailure(codeHash, onMatch));
        if (verdict == SetupCodeVerdict.NONE) {
            attempts.refund(client);
        }
        return verdict;
    }

    public void revoke() {
        unitOfWork.write(() -> {
            instanceSecretsRepository.clearSetupCode();
            unitOfWork.afterCommit(this::forgetCode);
        });
    }

    private SetupCodeVerdict consumeOrRecordFailure(String codeHash, Runnable onMatch) {
        Instant now = clock.instant();
        if (instanceSecretsRepository.consumeSetupCode(codeHash, now, LOCK_THRESHOLD)) {
            onMatch.run();
            unitOfWork.afterCommit(this::forgetCode);
            return SetupCodeVerdict.CONSUMED;
        }
        return instanceSecretsRepository.recordFailedSetupCodeAttempt(now)
            .map(failures -> failures >= LOCK_THRESHOLD ? SetupCodeVerdict.LOCKED : SetupCodeVerdict.INVALID)
            .orElse(SetupCodeVerdict.NONE);
    }

    private void forgetCode() {
        attempts.reset();
        setupCodeFile.delete();
    }

    private SetupPhase currentPhase() {
        return SetupPhase.of(instanceContext.instance());
    }

    private void announceIssued(SetupPhase phase, String code, IssuedSetupCode issued) {
        attempts.reset();
        announcer.announce(phase.codeKind(), code, issued);
    }

    private IssuedSetupCode issuedCode(String code, Instant now) {
        return new IssuedSetupCode(oneTimeCodeCodec.hash(code), now, now.plus(CODE_LIFETIME));
    }
}
