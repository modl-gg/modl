package gg.modl.backend.instance;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.instance.repository.InstanceRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.SetupStep;
import gg.modl.backend.server.data.SetupStepStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InstanceService {
    private final InstanceRepository instanceRepository;
    private final UnitOfWork unitOfWork;
    private final InstanceContext instanceContext;
    private final PublicUrls publicUrls;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public Server storedInstance() {
        return instanceRepository.find().orElseThrow(() -> new IllegalStateException("The instance has not been bootstrapped"));
    }

    public boolean claim(String adminEmail, String publicUrl) {
        return unitOfWork.write(() -> {
            String panelBefore = publicUrls.panel();
            boolean claimed = instanceRepository.claim(EmailAddressUtil.requireNormalized(adminEmail), publicUrl, clock.instant());
            unitOfWork.afterCommit(() -> refreshAndAnnounceUrlChange(panelBefore));
            return claimed;
        });
    }

    public Server changeAdminEmail(String adminEmail) {
        return change(now -> instanceRepository.updateAdminEmail(EmailAddressUtil.requireNormalized(adminEmail), now));
    }

    public Server assignConfiguredAdminEmail(String adminEmail) {
        return change(now -> instanceRepository.assignConfiguredAdminEmail(EmailAddressUtil.requireNormalized(adminEmail), now));
    }

    public boolean recordOwnerSignIn() {
        return unitOfWork.write(() -> {
            boolean recorded = instanceRepository.recordOwnerSignIn(clock.instant());
            if (recorded) {
                unitOfWork.afterCommit(instanceContext::refresh);
            }
            return recorded;
        });
    }

    public Server updateSetupStep(SetupStep step, SetupStepStatus status) {
        return change(now -> instanceRepository.updateSetupStep(step, status, now));
    }

    public Server completeSetup() {
        return change(instanceRepository::completeSetup);
    }

    public Server changeServerName(String serverName) {
        return change(now -> instanceRepository.updateServerName(serverName, now));
    }

    public Server changePublicUrl(@Nullable String publicUrl) {
        return unitOfWork.write(() -> {
            String panelBefore = publicUrls.panel();
            instanceRepository.updatePublicUrl(publicUrl, clock.instant());
            unitOfWork.afterCommit(() -> refreshAndAnnounceUrlChange(panelBefore));
            return storedInstance();
        });
    }

    public boolean capturePublicUrlIfUnknown(HttpOrigin observedOrigin) {
        if (publicUrls.knownOrigin().isPresent()) {
            return false;
        }
        return unitOfWork.write(() -> {
            String panelBefore = publicUrls.panel();
            boolean captured = instanceRepository.storePublicUrlIfUnset(observedOrigin.value(), clock.instant());
            if (captured) {
                unitOfWork.afterCommit(() -> refreshAndAnnounceUrlChange(panelBefore));
            }
            return captured;
        });
    }

    public Server changeApiKey(String apiKey) {
        return change(now -> instanceRepository.updateApiKey(apiKey, now));
    }

    private Server change(Consumer<Instant> write) {
        return unitOfWork.write(() -> {
            write.accept(clock.instant());
            unitOfWork.afterCommit(instanceContext::refresh);
            return storedInstance();
        });
    }

    private void refreshAndAnnounceUrlChange(String panelBefore) {
        instanceContext.refresh();
        String panelAfter = publicUrls.panel();
        if (!panelAfter.equals(panelBefore)) {
            eventPublisher.publishEvent(new PublicUrlChangedEvent(panelAfter));
        }
    }
}
