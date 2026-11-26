package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.core.imports.source.ImportProblem;
import gg.modl.minecraft.core.imports.source.ImportSource;
import gg.modl.minecraft.core.imports.source.PlayerRecords;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourceInventory;
import gg.modl.minecraft.core.imports.source.SourcePlayer;
import gg.modl.minecraft.core.imports.source.SourcePunishment;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.imports.source.UnreadablePlayerException;
import gg.modl.minecraft.core.imports.support.PlayerLimit;
import gg.modl.minecraft.core.imports.support.SourceSupport;
import gg.modl.minecraft.core.util.PluginLogger;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@RequiredArgsConstructor
final class ImportExporter {
    private static final int LISTED_FAILURES = 5;

    private final ImportSource source;
    private final SourceReader reader;
    private final ImportSignal signal;
    private final PluginLogger logger;
    private final Supplier<String> warningText;

    void export(Path target, SourceInventory inventory, ScopedPunishmentChoice choice, long maxBytes,
                ExportProgress progress, Instant importedAt) {
        int players = inventory.getPlayers();
        if (players > PlayerLimit.MAX_PLAYERS) {
            throw new ImportFailure(ImportEnding.EXPORT_FAILED, ImportProblem.TOO_MANY_PLAYERS, players + " players");
        }
        progress.begin(players, inventory.getWithoutAccount());
        try (ImportExportWriter writer = new ImportExportWriter(source.id(), target, maxBytes, players, importedAt,
                SourceSupport.plainText(warningText.get()))) {
            writeEverything(writer, choice, progress);
        } catch (IOException failure) {
            throw new ImportFailure(ImportEnding.EXPORT_FAILED, ImportProblem.FILE_FAILED, failure);
        }
    }

    private void writeEverything(ImportExportWriter writer, ScopedPunishmentChoice choice, ExportProgress progress)
            throws IOException {
        try {
            int written = writePlayers(writer, choice, progress);
            if (written == 0) {
                ImportProblem problem = progress.failed() > 0 ? ImportProblem.EVERY_PLAYER_FAILED : ImportProblem.NOTHING_LEFT;
                throw new ImportFailure(ImportEnding.EXPORT_FAILED, problem, "No player could be exported");
            }
            writer.finish();
        } catch (IOException failure) {
            if (writer.limitExceeded()) {
                throw ImportFailure.ended(ImportEnding.FILE_TOO_LARGE, failure);
            }
            throw failure;
        }
    }

    private int writePlayers(ImportExportWriter writer, ScopedPunishmentChoice choice, ExportProgress progress)
            throws IOException {
        PlayerRecords records = records();
        int written = 0;
        while (true) {
            if (signal.isSet()) throw ImportFailure.cancelled();
            SourcePlayer player;
            try {
                player = records.next();
            } catch (UnreadablePlayerException unreadable) {
                failed(unreadable, progress);
                continue;
            } catch (SourceException | RuntimeException failure) {
                throw sourceFailure(failure);
            }
            if (player == null) return written;
            List<SourcePunishment> kept = kept(player.getPunishments(), choice);
            progress.punishmentsSkipped(player.getPunishments().size() - kept.size());
            if (!kept.isEmpty() || player.hasHistory()) {
                writer.write(player, kept, choice == ScopedPunishmentChoice.WIDEN);
                written++;
            }
            progress.playerHandled();
        }
    }

    private PlayerRecords records() {
        try {
            return reader.players();
        } catch (SourceException failure) {
            throw sourceFailure(failure);
        }
    }

    private void failed(UnreadablePlayerException unreadable, ExportProgress progress) {
        if (signal.isSet()) throw ImportFailure.cancelled();
        progress.playerFailed();
        progress.playerHandled();
        if (progress.failed() <= LISTED_FAILURES) {
            logger.warning(source.displayName() + " import could not read player " + unreadable.getPlayer() + ": "
                    + unreadable.getMessage());
        }
    }

    private ImportFailure sourceFailure(Exception failure) {
        if (signal.isSet()) return ImportFailure.cancelled();
        return ImportFailure.fromSource(failure);
    }

    private static List<SourcePunishment> kept(List<SourcePunishment> punishments, ScopedPunishmentChoice choice) {
        return punishments.stream()
                .filter(punishment -> choice == ScopedPunishmentChoice.WIDEN || !punishment.getScope().isScoped())
                .filter(punishment -> !punishment.getKind().isNote() || !punishment.isLifted())
                .collect(Collectors.toList());
    }
}
