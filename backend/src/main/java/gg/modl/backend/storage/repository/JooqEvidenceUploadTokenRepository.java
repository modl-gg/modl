package gg.modl.backend.storage.repository;

import static gg.modl.backend.database.jooq.Tables.EVIDENCE_UPLOAD_TOKENS;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.records.EvidenceUploadTokensRecord;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqEvidenceUploadTokenRepository implements EvidenceUploadTokenRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;

    @Override
    public void insert(EvidenceUploadToken token) {
        EvidenceUploadTokensRecord row = new EvidenceUploadTokensRecord(
            TextLimits.require(EVIDENCE_UPLOAD_TOKENS.TOKEN_HASH, token.tokenHash()),
            TextLimits.require(EVIDENCE_UPLOAD_TOKENS.PUNISHMENT_ID, token.punishmentId()),
            TextLimits.require(EVIDENCE_UPLOAD_TOKENS.PLAYER_UUID, token.playerUuid()),
            TextLimits.requireIfPresent(EVIDENCE_UPLOAD_TOKENS.ISSUER_NAME, token.issuerName()),
            token.createdAt(),
            token.expiresAt());
        unitOfWork.write(() -> {
            dsl.insertInto(EVIDENCE_UPLOAD_TOKENS).set(row).execute();
        });
    }

    @Override
    public Optional<EvidenceUploadToken> findByTokenHash(String tokenHash) {
        if (!StorageKeyQueries.canBeStored(EVIDENCE_UPLOAD_TOKENS.TOKEN_HASH, tokenHash)) {
            return Optional.empty();
        }
        String bound = TextLimits.require(EVIDENCE_UPLOAD_TOKENS.TOKEN_HASH, tokenHash);
        return dsl.selectFrom(EVIDENCE_UPLOAD_TOKENS)
            .where(EVIDENCE_UPLOAD_TOKENS.TOKEN_HASH.eq(bound))
            .fetchOptional(JooqEvidenceUploadTokenRepository::toToken);
    }

    @Override
    public boolean deleteByTokenHash(String tokenHash) {
        if (!StorageKeyQueries.canBeStored(EVIDENCE_UPLOAD_TOKENS.TOKEN_HASH, tokenHash)) {
            return false;
        }
        String bound = TextLimits.require(EVIDENCE_UPLOAD_TOKENS.TOKEN_HASH, tokenHash);
        return unitOfWork.write(() -> dsl.deleteFrom(EVIDENCE_UPLOAD_TOKENS)
            .where(EVIDENCE_UPLOAD_TOKENS.TOKEN_HASH.eq(bound))
            .execute() == 1);
    }

    private static EvidenceUploadToken toToken(EvidenceUploadTokensRecord row) {
        return new EvidenceUploadToken(row.getTokenHash(), row.getPunishmentId(), row.getPlayerUuid(), row.getIssuerName(),
            row.getCreatedAt(), row.getExpiresAt());
    }
}
