package gg.modl.backend.status.facts;

import gg.modl.backend.email.EmailCapability;
import gg.modl.backend.status.StatusContributor;
import gg.modl.backend.status.StatusFact;
import gg.modl.backend.status.StatusItem;
import gg.modl.backend.status.StatusSection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EmailStatusFacts implements StatusContributor {
    private final EmailCapability emailCapability;

    @Override
    public StatusSection section() {
        return StatusSection.EMAIL;
    }

    @Override
    public List<StatusFact> facts() {
        return List.of(emailCapability.isDeliveryEnabled()
            ? StatusItem.EMAIL_DELIVERY.ok("SMTP configured")
            : StatusItem.EMAIL_DELIVERY.ok("not configured, codes are printed to the console"));
    }
}
