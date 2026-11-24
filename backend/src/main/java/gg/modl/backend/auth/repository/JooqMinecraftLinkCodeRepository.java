package gg.modl.backend.auth.repository;

import static gg.modl.backend.database.jooq.Tables.MINECRAFT_LINK_CODES;

import gg.modl.backend.auth.link.MinecraftLinkCode;
import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.Indexes;
import gg.modl.backend.database.jooq.tables.records.MinecraftLinkCodesRecord;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqMinecraftLinkCodeRepository implements MinecraftLinkCodeRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final InsertIfAbsent insertIfAbsent;

    @Override
    public void replaceForIdentity(MinecraftLinkCode code) {
        MinecraftLinkCodesRecord row = new MinecraftLinkCodesRecord(
            TextLimits.require(MINECRAFT_LINK_CODES.ID, code.getId()),
            TextLimits.require(MINECRAFT_LINK_CODES.CODE_HASH, code.getCodeHash()),
            AuthColumns.identity(MINECRAFT_LINK_CODES.IDENTITY_EMAIL, code.getIdentityEmail()),
            code.getExpiresAt(),
            code.getCreatedAt());
        unitOfWork.write(() -> {
            deleteOwnedBy(code.getIdentityEmail());
            if (!insertIfAbsent.on(MINECRAFT_LINK_CODES, Indexes.UQ_MINECRAFT_LINK_CODES_IDENTITY).insert(row)) {
                throw new ConcurrentUpdateException("Another link code was issued for this identity at the same time");
            }
        });
    }

    @Override
    public void deleteForIdentity(String identityEmail) {
        unitOfWork.write(() -> deleteOwnedBy(identityEmail));
    }

    @Override
    public Optional<MinecraftLinkCode> findActive(String codeHash, Instant now) {
        return dsl.selectFrom(MINECRAFT_LINK_CODES)
            .where(active(codeHash, now))
            .fetchOptional()
            .map(JooqMinecraftLinkCodeRepository::toLinkCode);
    }

    @Override
    public Optional<MinecraftLinkCode> consumeActive(String codeHash, Instant now) {
        return unitOfWork.write(() -> SingleUseRows.consume(
                dsl, MINECRAFT_LINK_CODES, MINECRAFT_LINK_CODES.ID, active(codeHash, now)))
            .map(JooqMinecraftLinkCodeRepository::toLinkCode);
    }

    private int deleteOwnedBy(String identityEmail) {
        return dsl.deleteFrom(MINECRAFT_LINK_CODES)
            .where(AuthColumns.matchesIdentity(MINECRAFT_LINK_CODES.IDENTITY_EMAIL, identityEmail))
            .execute();
    }

    private static Condition active(String codeHash, Instant now) {
        return ColumnLookups.matches(MINECRAFT_LINK_CODES.CODE_HASH, codeHash).and(MINECRAFT_LINK_CODES.EXPIRES_AT.gt(now));
    }

    private static MinecraftLinkCode toLinkCode(MinecraftLinkCodesRecord row) {
        return MinecraftLinkCode.builder()
            .id(row.getId())
            .codeHash(row.getCodeHash())
            .identityEmail(row.getIdentityEmail())
            .expiresAt(row.getExpiresAt())
            .createdAt(row.getCreatedAt())
            .build();
    }
}
