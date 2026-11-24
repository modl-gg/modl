package gg.modl.backend.hostedimport.job;

import gg.modl.backend.hostedimport.mapping.ArchiveIndex;
import gg.modl.backend.hostedimport.model.ImportPhase;

public interface PhaseStep {
    ImportPhase phase();

    long total(ArchiveIndex index);

    void run(PhaseRun run);
}
