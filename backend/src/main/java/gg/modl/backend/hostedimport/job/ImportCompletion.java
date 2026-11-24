package gg.modl.backend.hostedimport.job;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.hostedimport.intake.ImportArchiveFiles;
import gg.modl.backend.hostedimport.model.ImportJob;
import gg.modl.backend.hostedimport.model.ImportStatus;
import gg.modl.backend.instance.InstanceService;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.SetupStep;
import gg.modl.backend.server.data.SetupStepStatus;
import gg.modl.backend.storage.ConfirmedStorageTotal;
import java.util.Optional;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ImportCompletion {
    private final PanelActionAuditor auditor;
    private final InstanceService instanceService;
    private final ImportStates states;
    private final ImportArchiveFiles files;
    private final ArchiveSession session;
    private final ConfirmedStorageTotal confirmedStorageTotal;
    private final ImportNotices notices;
    private final UnitOfWork unitOfWork;

    public Optional<ImportJob> complete(Server server, String importId, ImportStatus expected, UnaryOperator<ImportJob> closing,
                                        String auditAction) {
        return unitOfWork.write(() -> states.transitionInUnit(importId, expected, closing)
            .map(finished -> {
                instanceService.updateSetupStep(SetupStep.IMPORT, SetupStepStatus.DONE);
                auditor.recordStaffAction(server, server.getAdminEmail(), auditAction);
                unitOfWork.afterCommit(() -> {
                    files.delete(importId);
                    session.forget();
                    confirmedStorageTotal.resync();
                    notices.finished(finished.tally());
                });
                return finished;
            }));
    }
}
