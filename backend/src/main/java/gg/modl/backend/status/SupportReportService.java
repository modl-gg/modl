package gg.modl.backend.status;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SupportReportService {
    private final List<StatusContributor> contributors;
    private final Clock clock;

    public SupportReportService(List<StatusContributor> contributors, Clock clock) {
        this.contributors = contributors.stream().sorted(Comparator.comparing(StatusContributor::section)).toList();
        this.clock = clock;
    }

    public SupportReport report() {
        List<StatusFact> facts = new ArrayList<>();
        for (StatusContributor contributor : contributors) {
            facts.addAll(factsOf(contributor));
        }
        facts.sort(Comparator.comparing((StatusFact fact) -> fact.item().section()).thenComparing(StatusFact::item));
        return new SupportReport(facts, clock.instant());
    }

    private static List<StatusFact> factsOf(StatusContributor contributor) {
        try {
            return contributor.facts();
        } catch (RuntimeException e) {
            log.warn("The {} status could not be read", contributor.section().title(), e);
            return List.of(new StatusFact(firstItemOf(contributor.section()), "could not be read ("
                + e.getClass().getSimpleName() + ")", StatusLevel.PROBLEM));
        }
    }

    private static StatusItem firstItemOf(StatusSection section) {
        for (StatusItem item : StatusItem.values()) {
            if (item.section() == section) {
                return item;
            }
        }
        throw new IllegalStateException("No status item belongs to " + section);
    }
}
