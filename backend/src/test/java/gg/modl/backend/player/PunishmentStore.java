package gg.modl.backend.player;

import static gg.modl.backend.database.jooq.Tables.IP_LOOKUPS;
import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_LINKS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static org.mockito.Mockito.mock;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.database.jooq.tables.records.PlayerLinksRecord;
import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import gg.modl.backend.iplookup.model.VpnStatus;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentData;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.repository.JooqPlayerPunishmentLock;
import gg.modl.backend.player.repository.JooqPlayerRepository;
import gg.modl.backend.player.repository.JooqPunishmentRepository;
import gg.modl.backend.player.repository.PlayerPunishmentLock;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.PlayerStatusCalculator;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.jooq.DSLContext;

public final class PunishmentStore {
    public static final Instant NOW = SqlTestDatabase.START;
    public static final int KICK = 0;
    public static final int MUTE = 1;
    public static final int BAN = 2;

    private final SqlTestDatabase database;
    private final DSLContext dsl;
    private final PunishmentRepository punishments;
    private final PlayerRepository players;
    private final PlayerPunishmentLock lock;

    private PunishmentStore(SqlTestDatabase database, DSLContext dsl) {
        this.database = database;
        this.dsl = dsl;
        this.punishments = new JooqPunishmentRepository(dsl, database.unitOfWork(), database.insertIfAbsent(),
            database.childRows(), database.jsonColumns());
        this.players = new JooqPlayerRepository(dsl, database.unitOfWork(), database.insertIfAbsent(), database.upserts(),
            database.childRows(), database.jsonColumns(), database.clock());
        this.lock = new JooqPlayerPunishmentLock(database.childRows());
    }

    public static PunishmentStore on(SqlTestDatabase database) {
        return new PunishmentStore(database, database.dsl());
    }

    public static PunishmentStore on(SqlTestDatabase database, DSLContext dsl) {
        return new PunishmentStore(database, dsl);
    }

    public PunishmentRepository punishments() {
        return punishments;
    }

    public PlayerRepository players() {
        return players;
    }

    public PlayerPunishmentLock lock() {
        return lock;
    }

    public static UUID uuidOf(String playerId) {
        return UUID.nameUUIDFromBytes(playerId.getBytes(StandardCharsets.UTF_8));
    }

    public String addPlayer(String id) {
        addPlayers(List.of(id), false);
        return id;
    }

    public void addPlayers(List<String> ids, boolean online) {
        database.unitOfWork().write(() -> {
            for (String id : ids) {
                PlayersRecord player = new PlayersRecord();
                player.setId(id);
                player.setMinecraftUuid(uuidOf(id).toString());
                player.setUsername("Player_" + id);
                player.setUsernameLower("player_" + id);
                player.setIsOnline(online);
                player.setCreatedAt(NOW);
                database.dsl().insertInto(PLAYERS).set(player).execute();
            }
        });
    }

    public void link(String playerId, String linkedPlayerId) {
        link(playerId, linkedPlayerId, true);
    }

    public void linkWithoutCleanAddress(String playerId, String linkedPlayerId) {
        link(playerId, linkedPlayerId, false);
    }

    public void flagSharedAddress(String playerId, String linkedPlayerId) {
        database.unitOfWork().write(() -> database.dsl().update(IP_LOOKUPS)
            .set(IP_LOOKUPS.VPN, VpnStatus.YES.name())
            .where(IP_LOOKUPS.IP.eq(sharedAddressOf(playerId, linkedPlayerId)))
            .execute());
    }

    public void crowdSharedAddress(String playerId, String linkedPlayerId, int otherPlayers) {
        String address = sharedAddressOf(playerId, linkedPlayerId);
        List<String> crowd = new ArrayList<>();
        for (int index = 0; index < otherPlayers; index++) {
            crowd.add("crowd-" + index + "-" + playerId);
        }
        addPlayers(crowd, false);
        database.unitOfWork().write(() -> crowd.forEach(member -> insertAddress(member, address)));
    }

    private static String sharedAddressOf(String playerId, String linkedPlayerId) {
        String pair = playerId.compareTo(linkedPlayerId) < 0 ? playerId + "|" + linkedPlayerId : linkedPlayerId + "|" + playerId;
        return "2001:db8::" + Integer.toHexString(pair.hashCode() & 0xffff);
    }

    private void link(String playerId, String linkedPlayerId, boolean sharedCleanAddress) {
        database.unitOfWork().write(() -> {
            insertLink(playerId, linkedPlayerId, sharedCleanAddress);
            insertLink(linkedPlayerId, playerId, sharedCleanAddress);
            if (sharedCleanAddress) {
                String address = sharedAddressOf(playerId, linkedPlayerId);
                insertAddress(playerId, address);
                insertAddress(linkedPlayerId, address);
                database.dsl().insertInto(IP_LOOKUPS)
                    .set(IP_LOOKUPS.IP, address)
                    .set(IP_LOOKUPS.PROVIDER, "test")
                    .set(IP_LOOKUPS.VPN, VpnStatus.NO.name())
                    .set(IP_LOOKUPS.LOOKED_UP_AT, NOW)
                    .onDuplicateKeyIgnore()
                    .execute();
            }
        });
    }

    private void insertAddress(String playerId, String address) {
        database.dsl().insertInto(PLAYER_IPS)
            .set(PLAYER_IPS.PLAYER_ID, playerId)
            .set(PLAYER_IPS.IP, address)
            .set(PLAYER_IPS.FIRST_SEEN_AT, NOW)
            .set(PLAYER_IPS.LAST_SEEN_AT, NOW)
            .onDuplicateKeyIgnore()
            .execute();
    }

    private void insertLink(String playerId, String linkedPlayerId, boolean sharedCleanAddress) {
        PlayerLinksRecord link = new PlayerLinksRecord();
        link.setPlayerId(playerId);
        link.setLinkedPlayerId(linkedPlayerId);
        link.setLinkedAt(NOW);
        link.setSharedCleanAddress(sharedCleanAddress);
        database.dsl().insertInto(PLAYER_LINKS).set(link).execute();
    }

    public void issue(String playerId, Punishment punishment, ResolvedCategory category) {
        if (!punishments.insertIfAbsent(playerId, punishment, category)) {
            throw new IllegalStateException(punishment.getId() + " already exists");
        }
    }

    public Punishment punishment(String id) {
        return punishments.findById(id).orElseThrow().punishment();
    }

    public int version(String id) {
        return punishments.findById(id).orElseThrow().version();
    }

    public Instant activeUntil(String id) {
        return dsl.select(PUNISHMENTS.ACTIVE_UNTIL_AT).from(PUNISHMENTS).where(PUNISHMENTS.ID.eq(id)).fetchSingle().value1();
    }

    @Nullable
    public Instant modifiedAt(String id) {
        return dsl.select(PUNISHMENTS.MODIFIED_AT).from(PUNISHMENTS).where(PUNISHMENTS.ID.eq(id)).fetchSingle().value1();
    }

    public static PlayerStatusCalculator calculatorAt(Instant now) {
        return calculator(Clock.fixed(now, ZoneOffset.UTC));
    }

    public static PlayerStatusCalculator calculator(Clock clock) {
        return new PlayerStatusCalculator(mock(PunishmentTypeService.class), mock(OffenderThresholdSettingsService.class), clock);
    }

    public static Punishment draft(String id, int typeOrdinal, @Nullable Long duration, @Nullable Instant started,
                                   @Nullable String status) {
        Map<String, Object> data = new HashMap<>();
        if (duration != null) {
            data.put(PunishmentData.DURATION, duration);
        }
        if (status != null) {
            data.put(PunishmentData.STATUS, status);
        }
        return new Punishment(id, typeOrdinal, "Mod", null, PunishmentIssuerKind.STAFF, NOW, started, new ArrayList<>(), new ArrayList<>(),
            new ArrayList<>(), new ArrayList<>(), data);
    }

    public static PunishmentModification change(String id, String type, Instant at, @Nullable Long effectiveDuration) {
        return new PunishmentModification(id, type, at, "Mod", null, "", effectiveDuration, null, null);
    }
}
