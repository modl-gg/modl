package gg.modl.backend.databasemove.service;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.selection.DatabaseConnection;
import gg.modl.backend.database.selection.DatabaseSelection;
import gg.modl.backend.database.sql.CopyTarget;
import gg.modl.backend.database.sql.DatabaseTargetException;
import gg.modl.backend.database.sql.DatabaseToolException;
import gg.modl.backend.database.sql.TargetInspection;
import gg.modl.backend.database.sql.TargetInspection.Contents;
import gg.modl.backend.infrastructure.util.FailureDescriptions;
import gg.modl.backend.instance.InstanceSecretsService;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class DatabaseTargets {
    private final DatabaseSelection selection;
    private final DataDirectoryProperties dataDirectory;
    private final InstanceSecretsService secrets;

    OpenedTarget assess(DatabaseConnection target) {
        if (SameDatabase.at(target.location(), selection.currentLocation())) {
            return OpenedTarget.refused(DatabaseTestResult.refused(DatabaseMoveCodes.SAME, "modl already uses this database", ""));
        }
        CopyTarget opened;
        try {
            opened = CopyTarget.open(target.properties(selection.configured()), dataDirectory);
        } catch (DatabaseTargetException e) {
            return OpenedTarget.refused(refusal(e));
        }
        try {
            DatabaseTestResult result = judge(target, opened.inspect(secrets.storageSigningKey()));
            if (!result.ok()) {
                opened.close();
                return OpenedTarget.refused(result);
            }
            return OpenedTarget.ready(result, opened);
        } catch (DatabaseTargetException e) {
            opened.close();
            return OpenedTarget.refused(refusal(e));
        } catch (DatabaseToolException | UncheckedIOException e) {
            opened.close();
            return OpenedTarget.refused(DatabaseTestResult.refused(DatabaseMoveCodes.FAILED, "The database could not be checked",
                FailureDescriptions.describe(e)));
        } catch (RuntimeException e) {
            opened.close();
            throw e;
        }
    }

    private DatabaseTestResult judge(DatabaseConnection target, TargetInspection inspection) {
        if (inspection.unsupportedVersion().isPresent()) {
            return DatabaseTestResult.refused(DatabaseMoveCodes.VERSION_UNSUPPORTED, "This database server is not supported",
                inspection.unsupportedVersion().get());
        }
        if (!inspection.missingPrivileges().isEmpty()) {
            return DatabaseTestResult.refused(DatabaseMoveCodes.PRIVILEGES_MISSING, "The database user is missing privileges",
                String.join(", ", inspection.missingPrivileges()));
        }
        if (holdsOtherData(target, inspection.contents())) {
            return DatabaseTestResult.refused(DatabaseMoveCodes.HOLDS_OTHER_DATA, "The database already holds another modl panel's data",
                "");
        }
        List<TargetWarning> warnings = new ArrayList<>();
        inspection.durabilityWarnings().forEach(warning -> warnings.add(new TargetWarning.Durability(warning)));
        ConnectionPrivacy.warning(target).ifPresent(warnings::add);
        if (selection.currentLocation().isBuiltIn() && !target.isBuiltIn()) {
            warnings.add(new TargetWarning.NoNightlyBackups());
        }
        return DatabaseTestResult.passed(inspection.serverVersion(), warnings,
            inspection.contents() == Contents.COPY_OF_THIS_INSTALLATION);
    }

    private boolean holdsOtherData(DatabaseConnection target, Contents contents) {
        return switch (contents) {
            case EMPTY, COPY_OF_THIS_INSTALLATION -> false;
            case UNFINISHED_COPY -> !target.location().equals(selection.choice().copyingTo());
            case OTHER_DATA -> true;
        };
    }

    private static DatabaseTestResult refusal(DatabaseTargetException failure) {
        return switch (failure.problem()) {
            case UNREACHABLE -> DatabaseTestResult.refused(DatabaseMoveCodes.UNREACHABLE, "The database server could not be reached",
                FailureDescriptions.describe(failure));
            case SIGN_IN_FAILED -> DatabaseTestResult.refused(DatabaseMoveCodes.SIGN_IN_FAILED,
                "The database refused the user name or password", "");
            case IN_USE -> DatabaseTestResult.refused(DatabaseMoveCodes.IN_USE, "Another modl server is using this database", "");
            case FAILED -> DatabaseTestResult.refused(DatabaseMoveCodes.FAILED, "The database could not be checked",
                FailureDescriptions.describe(failure));
        };
    }
}
