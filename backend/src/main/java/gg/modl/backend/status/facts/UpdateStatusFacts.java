package gg.modl.backend.status.facts;

import gg.modl.backend.status.StatusContributor;
import gg.modl.backend.status.StatusFact;
import gg.modl.backend.status.StatusItem;
import gg.modl.backend.status.StatusSection;
import gg.modl.backend.update.api.UpdateStatus;
import gg.modl.backend.update.api.UpdateStatusService;
import gg.modl.update.FailedUpdate;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UpdateStatusFacts implements StatusContributor {
    private final UpdateStatusService updates;

    @Override
    public StatusSection section() {
        return StatusSection.UPDATES;
    }

    @Override
    public List<StatusFact> facts() {
        UpdateStatus status = updates.status();
        return List.of(checks(status), available(status), lastFailure(status.lastFailure()));
    }

    private static StatusFact checks(UpdateStatus status) {
        if (!status.checksEnabled()) {
            return StatusItem.UPDATE_CHECKS.ok("off");
        }
        String last = status.lastCheckedAt() == null ? "not yet" : StatusFormats.instant(status.lastCheckedAt());
        String outcome = status.lastOutcome() == null ? "" : ", " + status.lastOutcome().name().toLowerCase(Locale.ROOT);
        return StatusItem.UPDATE_CHECKS.ok("on, last " + last + outcome);
    }

    private static StatusFact available(UpdateStatus status) {
        if (status.release() == null) {
            return StatusItem.UPDATE_AVAILABLE.ok("none");
        }
        return StatusItem.UPDATE_AVAILABLE.warning(status.release().release().manifest().version().toString());
    }

    private static StatusFact lastFailure(@Nullable FailedUpdate failure) {
        if (failure == null) {
            return StatusItem.UPDATE_LAST_FAILURE.ok("none");
        }
        return StatusItem.UPDATE_LAST_FAILURE.problem(failure.from() + " to " + failure.to() + " at "
            + StatusFormats.instant(failure.failedAt()) + (failure.restored() ? ", restored" : ""));
    }
}
