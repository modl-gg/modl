package gg.modl.backend.hostedimport.service;

import gg.modl.backend.hostedimport.model.ImportJob;
import java.util.List;
import java.util.Optional;

public record ImportStateView(Optional<ImportJob> job, boolean eligible, List<String> blockers, int maxChunkBytes) {
    public ImportStateView {
        blockers = List.copyOf(blockers);
    }
}
