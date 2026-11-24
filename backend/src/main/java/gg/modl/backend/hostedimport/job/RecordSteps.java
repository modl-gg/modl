package gg.modl.backend.hostedimport.job;

import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.mapping.ArchiveCollection;
import gg.modl.backend.hostedimport.mapping.ArchiveIndex;
import gg.modl.backend.hostedimport.mapping.LogMapper;
import gg.modl.backend.hostedimport.mapping.LogMapper.PendingAudit;
import gg.modl.backend.hostedimport.mapping.LogMapper.PendingPlayerLog;
import gg.modl.backend.hostedimport.mapping.PlayerMapper;
import gg.modl.backend.hostedimport.mapping.PlayerMapper.MappedPlayer;
import gg.modl.backend.hostedimport.mapping.ReplayMapper;
import gg.modl.backend.hostedimport.mapping.TicketMapper;
import gg.modl.backend.hostedimport.mapping.TicketMapper.SourcedTicket;
import gg.modl.backend.hostedimport.mapping.ImportedIds.IdKind;
import gg.modl.backend.hostedimport.model.ImportPhase;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.hostedimport.repository.HostedImportStateRepository;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.log.repository.ChatLogRepository;
import gg.modl.backend.log.repository.ChatLogRow;
import gg.modl.backend.log.repository.CommandLogRepository;
import gg.modl.backend.log.repository.CommandLogRow;
import gg.modl.backend.log.repository.ImportedAuditEntry;
import gg.modl.backend.log.repository.ImportedLogRepository;
import gg.modl.backend.log.repository.ImportedLogTable;
import gg.modl.backend.player.data.PlayerDataKeys;
import gg.modl.backend.player.repository.ImportedAddressRetention;
import gg.modl.backend.player.repository.PlayerImportRepository;
import gg.modl.backend.player.repository.PlayerNotification;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.SnapshotImportResult;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.repository.ReplayRepository;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.repository.TicketRepository;
import gg.modl.backend.ticket.service.TicketAccessKeys;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecordSteps {
    private static final String PLAYER_ID = "playerId";

    private final PlayerImportRepository playerImports;
    private final PlayerRepository players;
    private final StoredPlayerIds storedIds;
    private final TicketRepository tickets;
    private final TicketAccessKeys accessKeys;
    private final ReplayRepository replays;
    private final ChatLogRepository chatLogs;
    private final CommandLogRepository commandLogs;
    private final ImportedLogRepository importedLogs;
    private final LogIds logIds;
    private final HostedImportStateRepository stateRepository;
    private final PublicUrls publicUrls;

    List<PhaseStep> steps() {
        return List.of(
            step(ImportPhase.PLAYERS, ArchiveCollection.PLAYERS, this::players),
            step(ImportPhase.LINKS, ArchiveCollection.PLAYERS, this::links),
            step(ImportPhase.TICKETS, ArchiveCollection.TICKETS, this::tickets),
            step(ImportPhase.REPLAYS, ArchiveCollection.REPLAYS, this::replays),
            step(ImportPhase.CHAT_LOGS, ArchiveCollection.CHAT_LOGS, this::chatLogs),
            step(ImportPhase.COMMAND_LOGS, ArchiveCollection.COMMAND_LOGS, this::commandLogs),
            step(ImportPhase.AUDIT_LOGS, ArchiveCollection.LOGS, this::auditLogs));
    }

    private static PhaseStep step(ImportPhase phase, ArchiveCollection collection, Consumer<PhaseRun> body) {
        return new PhaseStep() {
            @Override
            public ImportPhase phase() {
                return phase;
            }

            @Override
            public long total(ArchiveIndex index) {
                return index.documents(collection);
            }

            @Override
            public void run(PhaseRun run) {
                body.accept(run);
            }
        };
    }

    private void players(PhaseRun run) {
        PlayerMapper mapper = new PlayerMapper(run.context());
        CollectionImport.<MappedPlayer>of(ArchiveCollection.PLAYERS, ImportSubject.PLAYERS)
            .mapper((document, line) -> mapper.map(document))
            .rowsOf(MappedPlayer::rows)
            .write(batch -> writePlayers(run, batch))
            .build()
            .run(run);
    }

    private void writePlayers(PhaseRun run, List<MappedPlayer> batch) {
        SnapshotImportResult result = playerImports.importSnapshots(batch.stream().map(MappedPlayer::snapshot).toList(),
            id -> run.context().ids().derive(IdKind.PUNISHMENT, "live-collision", id),
            run.choices().retention().keepsIpHistory() ? ImportedAddressRetention.KEPT : ImportedAddressRetention.STANDARD);
        result.renamedPunishmentIds().keySet().forEach(id -> run.issues().report(IssueCode.ID_ALIASED, id));
        for (MappedPlayer player : batch) {
            String uuid = player.snapshot().player().getMinecraftUuid().toString();
            String id = result.storedIdByUuid().get(uuid);
            if (id == null) {
                throw new IllegalStateException("The player import stored no row for " + uuid);
            }
            for (PlayerNotification notification : player.notifications()) {
                players.addNotification(id, notification);
            }
        }
    }

    private void links(PhaseRun run) {
        CollectionImport.<PlayerLinks>of(ArchiveCollection.PLAYERS, ImportSubject.LINKS)
            .mapper((document, line) -> links(document))
            .rowsOf(links -> 1 + links.linked().size())
            .write(batch -> writeLinks(run, batch))
            .build()
            .run(run);
    }

    private static Optional<PlayerLinks> links(ArchiveDocument document) {
        UUID uuid = document.uuid("minecraftUuid");
        if (uuid == null) {
            return Optional.empty();
        }
        Set<String> linked = new LinkedHashSet<>();
        document.document("data").ifPresent(data -> data.list(PlayerDataKeys.LINKED_ACCOUNTS).stream()
            .map(ArchiveDocument::uuidOf)
            .filter(other -> other != null && !other.equals(uuid))
            .forEach(other -> linked.add(other.toString())));
        return linked.isEmpty() ? Optional.empty() : Optional.of(new PlayerLinks(uuid.toString(), List.copyOf(linked)));
    }

    private void writeLinks(PhaseRun run, List<PlayerLinks> batch) {
        Set<String> uuids = new LinkedHashSet<>();
        batch.forEach(links -> {
            uuids.add(links.uuid());
            uuids.addAll(links.linked());
        });
        Map<String, String> ids = storedIds.byUuid(uuids);
        for (PlayerLinks links : batch) {
            String id = ids.get(links.uuid());
            List<String> linkedIds = new ArrayList<>();
            for (String linked : links.linked()) {
                String linkedId = ids.get(linked);
                if (linkedId == null) {
                    run.issues().report(IssueCode.REFERENCE_DANGLING, linked);
                } else {
                    linkedIds.add(linkedId);
                }
            }
            if (id != null && !linkedIds.isEmpty()) {
                players.linkUnverified(id, linkedIds, run.context().acceptedAt());
                run.issues().imported(ImportSubject.LINKS);
            }
        }
    }

    private void tickets(PhaseRun run) {
        TicketMapper mapper = new TicketMapper(run.context(), publicUrls::replayPath, ticket -> accessKeys.generate());
        CollectionImport.<SourcedTicket>of(ArchiveCollection.TICKETS, ImportSubject.TICKETS)
            .mapper((document, line) -> mapper.mapSourced(document))
            .rowsOf(sourced -> 1 + sourced.ticket().getReplies().size() + sourced.ticket().getNotes().size()
                + sourced.ticket().getTags().size() + sourced.ticket().getAssignedTo().size())
            .write(batch -> writeTickets(run, batch))
            .build()
            .run(run);
    }

    private void writeTickets(PhaseRun run, List<SourcedTicket> batch) {
        Map<String, String> aliases = new LinkedHashMap<>(stateRepository.aliases());
        boolean aliased = false;
        for (SourcedTicket sourced : batch) {
            Ticket ticket = sourced.ticket();
            ticket.setId(sourced.mappedId());
            if (tickets.insertIfAbsent(ticket)) {
                continue;
            }
            String alias = run.context().ticketIds().collisionAlias(sourced.sourceId());
            ticket.setId(alias);
            if (tickets.insertIfAbsent(ticket)) {
                aliases.put(sourced.sourceId(), alias);
                run.issues().report(IssueCode.ID_ALIASED, sourced.sourceId());
                aliased = true;
            } else {
                run.issues().report(IssueCode.RECORD_REJECTED, alias);
            }
        }
        if (aliased) {
            stateRepository.saveAliases(aliases);
        }
    }

    private void replays(PhaseRun run) {
        ReplayMapper mapper = new ReplayMapper(run.context());
        CollectionImport.<ReplayDocument>of(ArchiveCollection.REPLAYS, ImportSubject.REPLAYS)
            .mapper((document, line) -> mapper.map(document))
            .write(batch -> batch.forEach(replays::insert))
            .build()
            .run(run);
    }

    private void chatLogs(PhaseRun run) {
        LogMapper mapper = logMapper(run);
        CollectionImport.<PendingPlayerLog>of(ArchiveCollection.CHAT_LOGS, ImportSubject.CHAT_LOGS)
            .mapper(mapper::chat)
            .write(batch -> {
                List<StoredLog> stored = stored(ImportedLogTable.CHAT, batch, run);
                chatLogs.insertAll(stored.stream().map(log -> new ChatLogRow(log.id(), log.playerId(), log.source().username(),
                    log.source().text(), log.source().serverName(), log.source().sentAt())).toList());
            })
            .build()
            .run(run);
    }

    private void commandLogs(PhaseRun run) {
        LogMapper mapper = logMapper(run);
        CollectionImport.<PendingPlayerLog>of(ArchiveCollection.COMMAND_LOGS, ImportSubject.COMMAND_LOGS)
            .mapper(mapper::command)
            .write(batch -> {
                List<StoredLog> stored = stored(ImportedLogTable.COMMAND, batch, run);
                commandLogs.insertAll(stored.stream().map(log -> new CommandLogRow(log.id(), log.playerId(), log.source().username(),
                    log.source().text(), log.source().serverName(), log.source().sentAt())).toList());
            })
            .build()
            .run(run);
    }

    private List<StoredLog> stored(ImportedLogTable table, List<PendingPlayerLog> batch, PhaseRun run) {
        Map<String, String> usernames = new HashMap<>();
        batch.forEach(log -> usernames.putIfAbsent(log.uuid(), log.username()));
        Map<String, String> ids = players.findOrCreateIdsByMinecraftUuid(usernames, run.context().acceptedAt());
        List<Long> assigned = logIds.assign(table, batch.stream().map(PendingPlayerLog::preferredId).toList());
        List<StoredLog> stored = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            String playerId = ids.get(batch.get(i).uuid());
            if (playerId != null) {
                stored.add(new StoredLog(assigned.get(i), playerId, batch.get(i)));
            }
        }
        return stored;
    }

    private void auditLogs(PhaseRun run) {
        LogMapper mapper = logMapper(run);
        CollectionImport.<PendingAudit>of(ArchiveCollection.LOGS, ImportSubject.AUDIT_LOGS)
            .mapper(mapper::audit)
            .write(batch -> {
                Set<String> uuids = new LinkedHashSet<>();
                batch.stream().map(PendingAudit::playerUuid).filter(uuid -> uuid != null).forEach(uuids::add);
                Map<String, String> ids = storedIds.byUuid(uuids);
                List<Long> assigned = logIds.assign(ImportedLogTable.AUDIT, batch.stream().map(PendingAudit::preferredId).toList());
                List<ImportedAuditEntry> entries = new ArrayList<>();
                for (int i = 0; i < batch.size(); i++) {
                    PendingAudit audit = batch.get(i);
                    Map<String, Object> metadata = new LinkedHashMap<>(audit.metadata());
                    if (audit.playerUuid() != null && ids.containsKey(audit.playerUuid())) {
                        metadata.put(PLAYER_ID, ids.get(audit.playerUuid()));
                    }
                    entries.add(new ImportedAuditEntry(assigned.get(i), audit.level(), audit.source(), audit.description(), metadata,
                        audit.canRollback(), audit.createdAt()));
                }
                importedLogs.insertAudit(entries);
            })
            .build()
            .run(run);
    }

    private static LogMapper logMapper(PhaseRun run) {
        return new LogMapper(run.context(), run.choices().retention().chatLogDays(), run.choices().retention().auditLogDays());
    }

    private record PlayerLinks(String uuid, List<String> linked) {
    }

    private record StoredLog(long id, String playerId, PendingPlayerLog source) {
    }
}
