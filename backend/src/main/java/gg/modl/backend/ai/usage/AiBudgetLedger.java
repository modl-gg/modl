package gg.modl.backend.ai.usage;

import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiUsageLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.service.SettingsCodec;
import gg.modl.backend.settings.service.SettingsDocumentService;
import gg.modl.backend.settings.service.SettingsDocumentService.RawSettingsState;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class AiBudgetLedger {
    public static final String SETTINGS_TYPE = SettingsType.AI_USAGE.id();
    private static final SettingsCodec<AiUsageCounters> CODEC = SettingsCodec.of(AiUsageCounters.class, () -> AiUsageCounters.NONE);

    private final SettingsDocumentService documents;
    private final UnitOfWork unitOfWork;

    public AiBudgetLedger(SettingsDocumentService documents, UnitOfWork unitOfWork) {
        this.documents = documents;
        this.unitOfWork = unitOfWork;
    }

    public Optional<AiFailureKind> tryConsume(AiUsageLimits limits, Instant now) {
        return unitOfWork.write(() -> {
            RawSettingsState read = documents.getRawState(SETTINGS_TYPE);
            AiUsageCounters counters = CODEC.decode(read.data()).rolledTo(now);
            Optional<AiFailureKind> refusal = counters.refusal(limits);
            if (refusal.isEmpty()) {
                documents.updateFrom(SETTINGS_TYPE, read, data -> CODEC.encode(counters.incremented()));
            }
            return refusal;
        });
    }

    public AiUsageCounters current(Instant now) {
        return stored().rolledTo(now);
    }

    private AiUsageCounters stored() {
        return CODEC.decode(documents.getRawState(SETTINGS_TYPE).data());
    }
}
