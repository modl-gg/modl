package gg.modl.backend.status;

import java.util.List;

public interface StatusContributor {
    StatusSection section();

    List<StatusFact> facts();
}
