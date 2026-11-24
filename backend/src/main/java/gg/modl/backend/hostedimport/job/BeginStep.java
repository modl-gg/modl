package gg.modl.backend.hostedimport.job;

import gg.modl.backend.hostedimport.mapping.ArchiveIndex;
import gg.modl.backend.hostedimport.mapping.StaffRecords;
import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportPhase;
import gg.modl.backend.hostedimport.model.ImportReason;
import gg.modl.backend.hostedimport.model.RetentionPlan;
import gg.modl.backend.hostedimport.repository.HostedImportStateRepository;
import gg.modl.backend.settings.service.DataRetentionSettingsService;
import gg.modl.backend.settings.service.ReplayRetentionSettingsService;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.service.ImportedStaffAccounts;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BeginStep implements PhaseStep {
    private static final long BEGUN = 1;

    private final ImportEligibility eligibility;
    private final DataRetentionSettingsService dataRetention;
    private final ReplayRetentionSettingsService replayRetention;
    private final ImportedStaffAccounts staffAccounts;
    private final HostedImportStateRepository stateRepository;
    private final ImportPlanFactory planFactory;

    @Override
    public ImportPhase phase() {
        return ImportPhase.BEGIN;
    }

    @Override
    public long total(ArchiveIndex index) {
        return BEGUN;
    }

    @Override
    public void run(PhaseRun run) {
        if (run.cursor() >= BEGUN) {
            return;
        }
        if (!eligibility.dataBlockers(run.server()).isEmpty()) {
            throw new ImportFailure(ImportReason.NOT_ELIGIBLE);
        }
        Map<String, String> aliases = planFactory.liveTicketCollisions(run.index(), stateRepository.aliases());
        RetentionPlan retention = run.choices().retention();
        String owner = run.ownerEmail();
        run.units().commitState(run.job().id(), BEGUN, run.issues(), () -> {
            dataRetention.patchDataRetentionSettings(run.server(), owner,
                dataRetention.getDataRetentionSettingsState(run.server()).version(), retention.chatLogDays(), retention.auditLogDays());
            replayRetention.patchReplayRetentionSettings(run.server(), owner,
                replayRetention.getReplayRetentionSettingsState(run.server()).version(), retention.replayRetentionEnabled(),
                retention.replayDays());
            if (staffAccounts.ownerRecord(run.server()).isEmpty()) {
                Staff archived = run.plan().staff().ownerDocument(run.choices().mergeArchivedOwner())
                    .map(candidate -> StaffRecords.toStaff(candidate, run.context().fit(), run.context().exportTime()))
                    .orElse(null);
                staffAccounts.createOwnerRecord(run.server(), archived);
            }
            stateRepository.saveAliases(aliases);
        });
    }
}
