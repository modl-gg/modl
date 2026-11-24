package gg.modl.backend.status.facts;

import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.status.StatusContributor;
import gg.modl.backend.status.StatusFact;
import gg.modl.backend.status.StatusItem;
import gg.modl.backend.status.StatusSection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DatabaseStatusFacts implements StatusContributor {
    private final DatabaseDialect dialect;

    @Override
    public StatusSection section() {
        return StatusSection.DATABASE;
    }

    @Override
    public List<StatusFact> facts() {
        return List.of(StatusItem.DATABASE_TYPE.ok(dialect.displayName() + (dialect.isExternal() ? " (external)" : " (built in)")));
    }
}
