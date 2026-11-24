package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;

import gg.modl.backend.database.ChildRows;
import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.DuplicateRowException;
import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.GeneratedIdInsert;
import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.Upserts;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.Punishment;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class JooqPlayerImportRepository implements PlayerImportRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final JsonColumns json;
    private final PunishmentRepository punishmentRepository;
    private final PlayerWriter writer;
    private final ImportedPlayerRows importedRows;

    public JooqPlayerImportRepository(DSLContext dsl, UnitOfWork unitOfWork, InsertIfAbsent insertIfAbsent, Upserts upserts,
                                      ChildRows childRows, JsonColumns json, PunishmentRepository punishmentRepository) {
        this.dsl = dsl;
        this.unitOfWork = unitOfWork;
        this.json = json;
        this.punishmentRepository = punishmentRepository;
        this.writer = new PlayerWriter(insertIfAbsent, upserts, childRows);
        this.importedRows = new ImportedPlayerRows(dsl, upserts);
    }

    @Override
    public ImportBatchResult importBatch(List<Player> players, Instant now, ImportedAddressRetention retention) {
        List<Player> importable = players.stream()
            .filter(player -> player.getMinecraftUuid() != null)
            .sorted(Comparator.comparing(player -> player.getMinecraftUuid().toString()))
            .toList();
        int skipped = players.size() - importable.size();
        if (importable.isEmpty()) {
            return new ImportBatchResult(0, 0, skipped);
        }
        return unitOfWork.write(() -> importInKeyOrder(importable, skipped, now, retention));
    }

    @Override
    public SnapshotImportResult importSnapshots(List<PlayerSnapshot> snapshots, UnaryOperator<String> collisionId,
                                                ImportedAddressRetention retention) {
        List<PlayerSnapshot> ordered = snapshots.stream()
            .sorted(Comparator.comparing(snapshot -> snapshot.player().getMinecraftUuid().toString()))
            .toList();
        return unitOfWork.write(() -> importSnapshotsInKeyOrder(ordered, collisionId, retention));
    }

    private SnapshotImportResult importSnapshotsInKeyOrder(List<PlayerSnapshot> snapshots, UnaryOperator<String> collisionId,
                                                           ImportedAddressRetention retention) {
        List<Player> players = snapshots.stream().map(PlayerSnapshot::player).toList();
        Map<String, String> idsByUuid = existingIds(players);
        writer.lockInKeyOrder(idsByUuid.values());
        ImportedHistory history = ImportedHistory.load(dsl, json, idsByUuid.values());
        Map<String, String> renamed = new HashMap<>();
        int created = 0;
        int merged = 0;
        for (PlayerSnapshot snapshot : snapshots) {
            Player player = snapshot.player();
            String uuid = player.getMinecraftUuid().toString();
            String id = idsByUuid.get(uuid);
            if (id == null) {
                id = freePlayerId(player.getId());
                if (!writer.insertHeader(PlayerRows.header(id, player, json, snapshot.createdAt()))) {
                    throw new ConcurrentUpdateException("A player of the import was created while it ran");
                }
                idsByUuid.put(uuid, id);
                created++;
            } else {
                importedRows.mergeHeader(id, PlayerRows.header(id, player, json, snapshot.createdAt()));
                String imported = PlayerRows.currentUsername(player);
                if (imported != null) {
                    importedRows.nameUnknownPlayer(id, imported);
                }
                merged++;
            }
            PlayerRows.usernames(id, player.getUsernames(), snapshot.createdAt()).forEach(importedRows::mergeUsername);
            PlayerRows.addresses(id, player.getIpAddresses(), snapshot.createdAt())
                .forEach(row -> importedRows.mergeAddress(row, retention));
            for (NoteEntry note : player.getNotes()) {
                if (note.getId() == null || history.rememberNote(id, note.getId())) {
                    writer.appendNote(PlayerRows.note(id, note, snapshot.createdAt()));
                }
            }
            for (Punishment punishment : player.getPunishments()) {
                if (!insertPunishment(id, punishment)) {
                    String alternative = collisionId.apply(punishment.getId());
                    if (!insertPunishment(id, renamedCopy(punishment, alternative))) {
                        throw new DuplicateRowException("An imported punishment id is taken twice", null);
                    }
                    renamed.put(punishment.getId(), alternative);
                }
            }
        }
        return new SnapshotImportResult(idsByUuid, created, merged, renamed);
    }

    private static Punishment renamedCopy(Punishment source, String id) {
        Map<String, Object> data = source.data().asMap() == null ? new HashMap<>() : new HashMap<>(source.data().asMap());
        data.put(ImportedHistory.IMPORTED_ID, source.getId());
        return new Punishment(id, source.getTypeOrdinal(), source.getIssuerName(), source.getIssuerId(), source.getIssuerKind(), source.getIssued(),
            source.getStarted(), source.getModifications(), source.getNotes(), source.getEvidence(), source.getAttachedTicketIds(),
            data);
    }

    private String freePlayerId(String preferred) {
        boolean taken = dsl.fetchExists(dsl.selectOne().from(PLAYERS).where(PLAYERS.ID.eq(preferred)));
        return taken ? EntityIds.next() : preferred;
    }

    private ImportBatchResult importInKeyOrder(List<Player> players, int skipped, Instant now, ImportedAddressRetention retention) {
        Map<String, String> idsByUuid = existingIds(players);
        writer.lockInKeyOrder(idsByUuid.values());
        ImportedHistory history = ImportedHistory.load(dsl, json, idsByUuid.values());
        ImportedIssuers issuers = ImportedIssuers.resolve(dsl, players);

        int created = 0;
        for (Player player : players) {
            String uuid = player.getMinecraftUuid().toString();
            String id = idsByUuid.get(uuid);
            if (id == null) {
                id = EntityIds.next();
                if (!writer.insertHeader(PlayerRows.header(id, player, json, now))) {
                    throw new ConcurrentUpdateException("A player of the import was created while it ran");
                }
                idsByUuid.put(uuid, id);
                created++;
            } else {
                String imported = PlayerRows.currentUsername(player);
                if (imported != null) {
                    importedRows.nameUnknownPlayer(id, imported);
                }
            }
            PlayerRows.usernames(id, player.getUsernames(), now).forEach(importedRows::mergeUsername);
            PlayerRows.addresses(id, player.getIpAddresses(), now).forEach(row -> importedRows.mergeAddress(row, retention));
            importNotes(id, player.getNotes(), history, issuers, now);
            for (Punishment punishment : player.getPunishments()) {
                importPunishment(id, punishment, history, issuers);
            }
        }
        return new ImportBatchResult(created, players.size() - created, skipped);
    }

    private void importNotes(String playerId, List<NoteEntry> notes, ImportedHistory history, ImportedIssuers issuers,
                             Instant now) {
        for (NoteEntry note : notes) {
            if (note.getId() == null || history.rememberNote(playerId, note.getId())) {
                writer.appendNote(PlayerRows.note(playerId, issuers.playerNote(note), now));
            }
        }
    }

    private void importPunishment(String playerId, Punishment source, ImportedHistory history, ImportedIssuers issuers) {
        String sourceId = source.getId() == null ? "" : source.getId().trim();
        if (!sourceId.isEmpty() && !history.rememberPunishment(playerId, sourceId)) {
            return;
        }
        if (canAdopt(sourceId) && insertPunishment(playerId, issuers.punishment(source, sourceId, dataWithoutOrigin(source)))) {
            return;
        }
        Map<String, Object> data = dataWithoutOrigin(source);
        if (!sourceId.isEmpty()) {
            data.put(ImportedHistory.IMPORTED_ID, sourceId);
        }
        String generatedId = GeneratedIdInsert.insert(IdGenerator::generateShortId,
            id -> insertPunishment(playerId, issuers.punishment(source, id, data)),
            GeneratedIdInsert.DEFAULT_ATTEMPTS);
        history.rememberPunishment(playerId, generatedId);
    }

    private boolean insertPunishment(String playerId, Punishment punishment) {
        return punishmentRepository.insertIfAbsent(playerId, punishment, punishment.enforcementCategory());
    }

    private static boolean canAdopt(String sourceId) {
        return !sourceId.isEmpty() && TextLimits.canMatch(PUNISHMENTS.ID, sourceId);
    }

    private static Map<String, Object> dataWithoutOrigin(Punishment source) {
        Map<String, Object> original = source.data().asMap();
        Map<String, Object> data = original == null ? new HashMap<>() : new HashMap<>(original);
        data.remove(ImportedHistory.IMPORTED_ID);
        return data;
    }

    private Map<String, String> existingIds(List<Player> players) {
        List<String> uuids = players.stream().map(player -> player.getMinecraftUuid().toString()).toList();
        Map<String, String> ids = new HashMap<>();
        for (List<String> chunk : InClauseChunks.<String>of(uuids)) {
            dsl.select(PLAYERS.MINECRAFT_UUID, PLAYERS.ID)
                .from(PLAYERS)
                .where(PLAYERS.MINECRAFT_UUID.in(chunk))
                .fetch()
                .forEach(row -> ids.put(row.value1(), row.value2()));
        }
        return ids;
    }
}
