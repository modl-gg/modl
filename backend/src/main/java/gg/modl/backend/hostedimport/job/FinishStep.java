package gg.modl.backend.hostedimport.job;

import gg.modl.backend.hostedimport.mapping.ArchiveIndex;
import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportPhase;
import gg.modl.backend.hostedimport.model.ImportReason;
import gg.modl.backend.hostedimport.model.ImportStatus;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FinishStep implements PhaseStep {
    private final ImportCompletion completion;
    private final Clock clock;

    @Override
    public ImportPhase phase() {
        return ImportPhase.FINISH;
    }

    @Override
    public long total(ArchiveIndex index) {
        return 1;
    }

    @Override
    public void run(PhaseRun run) {
        run.requireRunning();
        boolean completed = completion.complete(run.server(), run.job().id(), ImportStatus.APPLYING,
                job -> job.completed(run.issues().tally(), clock.instant()).withPhaseDone(1),
                "Imported the data of modl.gg export " + run.index().manifest().exportId())
            .isPresent();
        if (!completed) {
            throw new ImportFailure(ImportReason.CANCELLED);
        }
    }
}
