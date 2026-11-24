package gg.modl.minecraft.core.imports;

import gg.modl.address.IpLiteral;
import gg.modl.minecraft.api.http.response.ImportJob;
import gg.modl.minecraft.api.http.response.ImportStatusResponse;
import gg.modl.minecraft.core.imports.source.ImportSource;
import gg.modl.minecraft.core.imports.source.SourceInventory;
import gg.modl.minecraft.core.locale.LocaleManager;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@RequiredArgsConstructor
final class ImportReporter {
    private static final long BYTES_PER_HISTORY_ENTRY = 150;
    private static final long BYTES_PER_MEBIBYTE = 1024 * 1024;
    private static final String NOT_REPORTED = "-";

    private final LocaleManager locale;
    private final Consumer<String> console;
    private final ImportSources sources;

    void say(String key) {
        console.accept(locale.getMessage(key));
    }

    void say(String key, Map<String, String> values) {
        console.accept(locale.getMessageWithRawValues(key, values));
    }

    void say(ImportSource source, String key) {
        say(key, sourceValues(source));
    }

    void say(ImportSource source, String key, Map<String, String> values) {
        Map<String, String> merged = sourceValues(source);
        merged.putAll(values);
        say(key, merged);
    }

    void supported(List<String> detected) {
        Map<String, String> values = mapOf("supported", sources.supportedIds(), "detected", String.join(", ", detected));
        say(detected.isEmpty() ? "import.nothing_detected" : "import.detected", values);
    }

    void unknownSource(String sourceId) {
        say("import.unknown_source", mapOf("source", sourceId, "supported", sources.supportedIds()));
    }

    void preview(ImportSource source, SourceInventory inventory, long oldHistoryEntries, ImportStatusResponse status) {
        Map<String, String> values = sourceValues(source);
        values.put("bans", String.valueOf(inventory.getBans()));
        values.put("mutes", String.valueOf(inventory.getMutes()));
        values.put("warnings", String.valueOf(inventory.getWarnings()));
        values.put("kicks", String.valueOf(inventory.getKicks()));
        values.put("notes", String.valueOf(inventory.getNotes()));
        values.put("players", String.valueOf(inventory.getPlayers()));
        values.put("ip_wide", String.valueOf(inventory.getIpWide()));
        values.put("server_scoped", String.valueOf(inventory.getServerScoped()));
        values.put("without_account", String.valueOf(inventory.getWithoutAccount()));
        values.put("days", String.valueOf(status.getIpHistoryDays()));
        values.put("old_entries", String.valueOf(oldHistoryEntries));
        values.put("old_megabytes", String.valueOf(mebibytesRoundedUp(oldHistoryEntries * BYTES_PER_HISTORY_ENTRY)));
        say("import.preview.counts", values);
        if (inventory.getWithoutAccount() > 0) say("import.preview.not_imported", values);
        say("import.preview.scoped", values);
        say("import.preview.ip_history", values);
        if (status.getIpHistoryKeptBefore() != null) say("import.preview.ip_history_kept", values);
        say("import.preview.backup");
        say("import.preview.confirm", values);
    }

    void status(ImportStatusResponse response) {
        ImportJob job = response.getJob();
        if (job == null) {
            say("import.status.none");
        } else if (ImportJobs.isActive(job)) {
            say("import.status." + job.getStatus(), valuesOf(job));
            say("import.status.details", valuesOf(job));
        } else {
            result(job);
            say("import.status.details", valuesOf(job));
        }
    }

    void alreadyRunning(ImportJob job) {
        say("import.already_running", valuesOf(job));
    }

    void processing(ImportJob job) {
        say("import.status.processing_data", valuesOf(job));
    }

    void result(ImportJob job) {
        if (ImportJobs.isCompleted(job)) {
            boolean complete = valueOf(job.getExportFailed()) == 0;
            say(complete ? "import.completed" : "import.completed_partly", valuesOf(job));
        } else {
            say(ImportEnding.fromWire(job.getEndReason()).messageKey(), valuesOf(job));
        }
    }

    void failed(String key, @Nullable String detail) {
        say(key, mapOf("error", text(detail)));
    }

    void failed(ImportSource source, String key, @Nullable String detail) {
        say(source, key, mapOf("error", text(detail)));
    }

    private static Map<String, String> sourceValues(ImportSource source) {
        Map<String, String> values = new HashMap<>();
        values.put("source", source.displayName());
        values.put("source_id", source.id());
        return values;
    }

    private Map<String, String> valuesOf(ImportJob job) {
        Map<String, String> values = new HashMap<>();
        values.put("source", sources.displayNameOf(job.getType()));
        values.put("source_id", text(job.getType()));
        values.put("server", text(job.getStartedFrom()));
        values.put("address", text(job.getStartedAddress() == null ? null : IpLiteral.display(job.getStartedAddress())));
        values.put("processed", String.valueOf(valueOf(job.getRecordsProcessed())));
        values.put("skipped", String.valueOf(valueOf(job.getRecordsSkipped())));
        values.put("total", String.valueOf(valueOf(job.getTotalRecords())));
        values.put("failed", String.valueOf(valueOf(job.getExportFailed())));
        values.put("not_imported", String.valueOf(valueOf(job.getExportSkipped())));
        values.put("scoped_choice", ScopedPunishmentChoice.wordOf(job.getScopedPunishments()));
        values.put("history_choice", IpHistoryChoice.wordOf(job.getIpHistory()));
        values.put("error", text(job.getError()));
        return values;
    }

    private static long mebibytesRoundedUp(long bytes) {
        return (bytes + BYTES_PER_MEBIBYTE - 1) / BYTES_PER_MEBIBYTE;
    }

    private static int valueOf(Integer count) {
        return count != null ? count : 0;
    }

    private static String text(@Nullable String value) {
        return value == null || value.isEmpty() ? NOT_REPORTED : value;
    }
}
