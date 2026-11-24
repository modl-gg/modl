package gg.modl.backend.hostedimport.job;

import gg.modl.backend.databasemove.DatabaseMoves;
import gg.modl.backend.hostedimport.model.ImportJob;
import gg.modl.backend.hostedimport.repository.ImportTargetRepository;
import gg.modl.backend.migration.service.ImportKind;
import gg.modl.backend.migration.service.RunningImports;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.SetupProgress;
import gg.modl.backend.storage.StorageMoves;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ImportEligibility {
    public static final String SETUP_COMPLETED = "SETUP_COMPLETED";
    public static final String ALREADY_IMPORTED = "ALREADY_IMPORTED";
    public static final String HAS_PUNISHMENTS = "HAS_PUNISHMENTS";
    public static final String HAS_STAFF = "HAS_STAFF";
    public static final String HAS_ARTICLES = "HAS_ARTICLES";
    public static final String PLUGIN_IMPORT_ACTIVE = "PLUGIN_IMPORT_ACTIVE";
    public static final String STORAGE_MOVING = "STORAGE_MOVING";
    public static final String DATABASE_MOVING = "DATABASE_MOVING";

    private final ImportTargetRepository targets;
    private final RunningImports runningImports;
    private final StorageMoves storageMoves;
    private final DatabaseMoves databaseMoves;
    private final Clock clock;

    public List<String> blockers(Server server, Optional<ImportJob> current) {
        List<String> blockers = new ArrayList<>();
        SetupProgress setup = server.getSetup();
        if (setup == null || setup.isCompleted()) {
            blockers.add(SETUP_COMPLETED);
        }
        if (current.filter(job -> job.status().isFinal()).isPresent()) {
            blockers.add(ALREADY_IMPORTED);
        }
        blockers.addAll(dataBlockers(server));
        return List.copyOf(blockers);
    }

    public List<String> dataBlockers(Server server) {
        List<String> blockers = new ArrayList<>();
        if (targets.hasPunishments()) {
            blockers.add(HAS_PUNISHMENTS);
        }
        if (targets.hasStaffOtherThan(Objects.requireNonNullElse(server.getAdminEmail(), ""))
            || targets.hasActiveInvitations(clock.instant())) {
            blockers.add(HAS_STAFF);
        }
        if (targets.hasArticlesBesidesStarters()) {
            blockers.add(HAS_ARTICLES);
        }
        if (runningImports.anyBesides(ImportKind.HOSTED)) {
            blockers.add(PLUGIN_IMPORT_ACTIVE);
        }
        if (storageMoves.isMovingFiles()) {
            blockers.add(STORAGE_MOVING);
        }
        if (databaseMoves.isMovingDatabase()) {
            blockers.add(DATABASE_MOVING);
        }
        return blockers;
    }
}
