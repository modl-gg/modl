package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.api.http.response.ImportStatusResponse;
import gg.modl.minecraft.core.imports.source.ImportSource;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourceInventory;
import gg.modl.minecraft.core.imports.source.SourceReader;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

@RequiredArgsConstructor
final class ImportPreview {
    private final ImportRuntime runtime;
    private final ImportSource source;
    private final AtomicReference<@Nullable SourceReader> previewing;
    private final PreviewedReader previewed;

    boolean show() {
        SourceReader reader = ImportSourceAccess.open(source);
        previewing.set(reader);
        boolean kept = false;
        try {
            SourceInventory inventory = reader.inventory();
            if (inventory.isEmpty()) {
                runtime.getReporter().say(source, "import.nothing_to_import");
                return false;
            }
            ImportStatusResponse status = runtime.getCalls().once(api -> api.getImportStatus(), runtime.getLifecycle());
            if (ImportJobs.isActive(status.getJob())) {
                runtime.getReporter().alreadyRunning(status.getJob());
                return false;
            }
            if (!status.isSetupReady()) {
                runtime.getReporter().say(source, "import.setup_required");
                return false;
            }
            long cutoff = runtime.getClock().getAsLong() - Duration.ofDays(status.getIpHistoryDays()).toMillis();
            long oldEntries = reader.historyEntriesBefore(cutoff);
            runtime.getReporter().preview(source, inventory, oldEntries, status);
            previewed.keep(source.id(), reader);
            kept = true;
            return true;
        } catch (SourceException failure) {
            if (runtime.getLifecycle().isSet()) throw ImportFailure.cancelled();
            throw ImportFailure.fromSource(failure);
        } finally {
            previewing.set(null);
            if (!kept) reader.close();
        }
    }
}
