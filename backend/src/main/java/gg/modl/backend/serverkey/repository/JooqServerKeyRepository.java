package gg.modl.backend.serverkey.repository;

import static gg.modl.backend.database.jooq.Tables.SERVER_KEYS;

import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitFrame;
import gg.modl.backend.database.jooq.tables.records.ServerKeysRecord;
import gg.modl.backend.serverkey.ServerKey;
import gg.modl.backend.serverkey.ServerKeyScope;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqServerKeyRepository implements ServerKeyRepository {
    private final DSLContext dsl;
    private final InsertIfAbsent insertIfAbsent;

    @Override
    public boolean insertIfAbsent(ServerKey key) {
        ServerKeysRecord row = new ServerKeysRecord();
        row.setId(TextLimits.require(SERVER_KEYS.ID, key.id()));
        row.setName(TextLimits.require(SERVER_KEYS.NAME, key.name()));
        row.setNameLower(TextLimits.require(SERVER_KEYS.NAME_LOWER, lowerCase(key.name())));
        row.setScope(key.scope().name());
        row.setAllowProxyCommands(key.allowProxyCommands());
        row.setKeyHash(TextLimits.require(SERVER_KEYS.KEY_HASH, key.keyHash()));
        row.setKeyHint(TextLimits.require(SERVER_KEYS.KEY_HINT, key.keyHint()));
        row.setCreatedByEmail(TextLimits.requireIfPresent(SERVER_KEYS.CREATED_BY_EMAIL, key.createdByEmail()));
        row.setCreatedAt(key.createdAt());
        row.setLastSeenAt(key.lastSeenAt());
        return insertIfAbsent.onAnyUnique(SERVER_KEYS).insert(row);
    }

    @Override
    public Optional<ServerKey> findByKeyHash(String keyHash) {
        if (!TextLimits.canMatch(SERVER_KEYS.KEY_HASH, keyHash)) {
            return Optional.empty();
        }
        return dsl.selectFrom(SERVER_KEYS)
            .where(ColumnLookups.matches(SERVER_KEYS.KEY_HASH, keyHash))
            .fetchOptional()
            .map(JooqServerKeyRepository::toKey);
    }

    @Override
    public Optional<ServerKey> findById(String id) {
        if (!TextLimits.canMatch(SERVER_KEYS.ID, id)) {
            return Optional.empty();
        }
        return dsl.selectFrom(SERVER_KEYS)
            .where(ColumnLookups.matches(SERVER_KEYS.ID, id))
            .fetchOptional()
            .map(JooqServerKeyRepository::toKey);
    }

    @Override
    public boolean existsByName(String name) {
        String lowered = lowerCase(name);
        if (!TextLimits.canMatch(SERVER_KEYS.NAME_LOWER, lowered)) {
            return false;
        }
        return dsl.fetchExists(SERVER_KEYS, ColumnLookups.matches(SERVER_KEYS.NAME_LOWER, lowered));
    }

    @Override
    public List<ServerKey> findAll() {
        return dsl.selectFrom(SERVER_KEYS)
            .orderBy(SERVER_KEYS.CREATED_AT, SERVER_KEYS.ID)
            .fetch(JooqServerKeyRepository::toKey);
    }

    @Override
    public int count() {
        return dsl.fetchCount(SERVER_KEYS);
    }

    @Override
    public boolean setAllowProxyCommands(String id, boolean allow) {
        UnitFrame.requireWrite();
        return dsl.update(SERVER_KEYS)
            .set(SERVER_KEYS.ALLOW_PROXY_COMMANDS, allow)
            .where(ColumnLookups.matches(SERVER_KEYS.ID, id))
            .execute() == 1;
    }

    @Override
    public boolean delete(String id) {
        UnitFrame.requireWrite();
        return dsl.deleteFrom(SERVER_KEYS)
            .where(ColumnLookups.matches(SERVER_KEYS.ID, id))
            .execute() == 1;
    }

    @Override
    public boolean recordSeen(String id, Instant seenAt, Instant onlyIfSeenBefore) {
        UnitFrame.requireWrite();
        return dsl.update(SERVER_KEYS)
            .set(SERVER_KEYS.LAST_SEEN_AT, seenAt)
            .where(ColumnLookups.matches(SERVER_KEYS.ID, id))
            .and(SERVER_KEYS.LAST_SEEN_AT.isNull().or(SERVER_KEYS.LAST_SEEN_AT.lt(onlyIfSeenBefore)))
            .execute() == 1;
    }

    private static String lowerCase(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static ServerKey toKey(ServerKeysRecord row) {
        return new ServerKey(
            row.getId(),
            row.getName(),
            ServerKeyScope.valueOf(row.getScope()),
            Boolean.TRUE.equals(row.getAllowProxyCommands()),
            row.getKeyHash(),
            row.getKeyHint(),
            row.getCreatedByEmail(),
            row.getCreatedAt(),
            row.getLastSeenAt()
        );
    }
}
