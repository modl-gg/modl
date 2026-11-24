package gg.modl.backend.hostedimport.job;

import gg.modl.backend.hostedimport.archive.ArchiveManifest;
import gg.modl.backend.hostedimport.archive.HostedArchive;
import gg.modl.backend.hostedimport.intake.ImportArchiveFiles;
import gg.modl.backend.hostedimport.mapping.ArchiveAnalyzer;
import gg.modl.backend.hostedimport.mapping.ArchiveIndex;
import gg.modl.backend.hostedimport.model.ImportChoices;
import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportJob;
import gg.modl.backend.hostedimport.model.ImportPhase;
import gg.modl.backend.hostedimport.model.ImportReason;
import gg.modl.backend.hostedimport.model.ImportStatus;
import gg.modl.backend.hostedimport.model.IssueLog;
import gg.modl.backend.hostedimport.repository.HostedImportStateRepository;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.player.config.IpHistoryPolicy;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.service.ImportedStaffAccounts;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

@Component
public class ImportApplier {
    private final ImportStates states;
    private final HostedImportStateRepository stateRepository;
    private final ImportArchiveFiles files;
    private final ArchiveSession session;
    private final ImportOutcomes outcomes;
    private final ImportWorker worker;
    private final ImportNotices notices;
    private final ImportPlanFactory planFactory;
    private final RecordUnits units;
    private final ImportedStaffAccounts staffAccounts;
    private final InstanceContext instanceContext;
    private final IpHistoryPolicy ipHistory;
    private final Clock clock;
    private final Map<ImportPhase, PhaseStep> steps = new EnumMap<>(ImportPhase.class);

    public ImportApplier(ImportStates states, HostedImportStateRepository stateRepository, ImportArchiveFiles files,
                         ArchiveSession session, ImportOutcomes outcomes, ImportWorker worker, ImportNotices notices,
                         ImportPlanFactory planFactory, RecordUnits units, ImportedStaffAccounts staffAccounts,
                         InstanceContext instanceContext, IpHistoryPolicy ipHistory, Clock clock, BeginStep begin,
                         ConfigurationStep configuration, FilesStep filesStep, RecordSteps records, AccessSteps access,
                         FinishStep finish) {
        this.states = states;
        this.stateRepository = stateRepository;
        this.files = files;
        this.session = session;
        this.outcomes = outcomes;
        this.worker = worker;
        this.notices = notices;
        this.planFactory = planFactory;
        this.units = units;
        this.staffAccounts = staffAccounts;
        this.instanceContext = instanceContext;
        this.ipHistory = ipHistory;
        this.clock = clock;
        List<PhaseStep> all = new ArrayList<>(List.of(begin, configuration, filesStep));
        all.addAll(records.steps());
        all.addAll(access.steps());
        all.add(finish);
        all.forEach(step -> steps.put(step.phase(), step));
        for (ImportPhase phase : ImportPhase.values()) {
            if (phase != ImportPhase.VERIFY && !steps.containsKey(phase)) {
                throw new IllegalStateException("No import step handles " + phase);
            }
        }
    }

    public void apply(String importId, boolean resumedAfterRestart) {
        BooleanSupplier stop = worker.stopSignal(importId);
        try {
            Optional<ImportJob> job = states.current(importId, ImportStatus.APPLYING);
            if (job.isEmpty()) {
                return;
            }
            if (resumedAfterRestart) {
                notices.resumedAfterRestart();
            }
            run(job.get(), stop);
        } catch (ImportFailure failure) {
            if (failure.reason() == ImportReason.NOT_ELIGIBLE) {
                outcomes.failed(importId, ImportStatus.APPLYING, failure, stop);
            } else {
                outcomes.paused(importId, failure, stop);
            }
        } catch (RuntimeException failure) {
            outcomes.paused(importId, failure, stop);
        }
    }

    private void run(ImportJob started, BooleanSupplier stop) {
        ImportChoices choices = stateRepository.choices().orElseThrow(() -> new ImportFailure(ImportReason.UNEXPECTED));
        try (HostedArchive archive = HostedArchive.open(files.pathOf(started.id()))) {
            ArchiveIndex index = session.indexFor(started.id()).orElseGet(() -> reanalyse(started, archive, stop));
            ImportJob job = started;
            while (job.status() == ImportStatus.APPLYING) {
                if (stop.getAsBoolean()) {
                    return;
                }
                job = runPhase(job, index, choices, archive, stop);
            }
        }
    }

    private ImportJob runPhase(ImportJob job, ArchiveIndex index, ImportChoices choices, HostedArchive archive, BooleanSupplier stop) {
        Server server = instanceContext.instance();
        String ownerEmail = server.getAdminEmail();
        if (ownerEmail == null) {
            throw new ImportFailure(ImportReason.NOT_ELIGIBLE);
        }
        IssueLog issues = new IssueLog();
        String ownerStaffId = staffAccounts.ownerRecord(server).map(Staff::getId).orElse(null);
        ImportPlan plan = planFactory.plan(server, index, issues, job.requireAcceptedAt(), choices, stateRepository.aliases(), ownerStaffId);
        if (job.phase() != ImportPhase.CONFIGURATION) {
            issues.clear();
        }
        ImportPhase phase = job.phase();
        step(phase).run(PhaseRun.builder()
            .job(job)
            .server(server)
            .ownerEmail(ownerEmail)
            .archive(archive)
            .plan(plan)
            .choices(choices)
            .units(units)
            .files(files)
            .stopRequested(stop)
            .build());
        ImportJob after = states.current().orElseThrow(() -> new ImportFailure(ImportReason.CANCELLED));
        if (after.status() != ImportStatus.APPLYING) {
            return after;
        }
        ImportPhase next = phase.next().orElseThrow(() -> new IllegalStateException("The last import phase did not finish the import"));
        return states.transition(after.id(), ImportStatus.APPLYING,
                current -> current.enteringPhase(next, clock.instant()).withPhaseTotal(step(next).total(index)))
            .orElseThrow(() -> new ImportFailure(ImportReason.CANCELLED));
    }

    private PhaseStep step(ImportPhase phase) {
        PhaseStep step = steps.get(phase);
        if (step == null) {
            throw new IllegalStateException("No import step handles " + phase);
        }
        return step;
    }

    private ArchiveIndex reanalyse(ImportJob job, HostedArchive archive, BooleanSupplier stop) {
        if (files.size(job.id()) != job.sizeBytes() || job.archiveSha256() == null
            || !job.archiveSha256().equals(files.sha256(job.id(), stop))) {
            throw new ImportFailure(ImportReason.ARCHIVE_MISSING);
        }
        ArchiveManifest manifest = ImportVerifier.manifest(archive);
        ArchiveIndex index = new ArchiveAnalyzer(archive, manifest, job.requireAcceptedAt(), ipHistory.ipHistoryDays(), stop)
            .analyse(false, done -> { });
        session.remember(job.id(), index);
        return index;
    }
}
