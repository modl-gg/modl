package gg.modl.backend.auth.repository;

import static gg.modl.backend.database.jooq.Tables.PANEL_LOGIN_TOKENS;

import gg.modl.backend.auth.link.PanelLoginToken;
import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.records.PanelLoginTokensRecord;
import gg.modl.backend.infrastructure.util.UuidUtils;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqPanelLoginTokenRepository implements PanelLoginTokenRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;

    @Override
    public void insert(PanelLoginToken token) {
        PanelLoginTokensRecord row = new PanelLoginTokensRecord(
            TextLimits.require(PANEL_LOGIN_TOKENS.ID, token.getId()),
            TextLimits.require(PANEL_LOGIN_TOKENS.TOKEN_HASH, token.getTokenHash()),
            AuthColumns.identity(PANEL_LOGIN_TOKENS.IDENTITY_EMAIL, token.getIdentityEmail()),
            TextLimits.requireIfPresent(PANEL_LOGIN_TOKENS.MINECRAFT_UUID, UuidUtils.normalizeIfPresent(token.getMinecraftUuid())),
            token.getExpiresAt(),
            token.getCreatedAt());
        unitOfWork.write(() -> dsl.insertInto(PANEL_LOGIN_TOKENS).set(row).execute());
    }

    @Override
    public void deleteForIdentity(String identityEmail) {
        unitOfWork.write(() -> dsl.deleteFrom(PANEL_LOGIN_TOKENS)
            .where(AuthColumns.matchesIdentity(PANEL_LOGIN_TOKENS.IDENTITY_EMAIL, identityEmail))
            .execute());
    }

    @Override
    public Optional<PanelLoginToken> consumeActive(String tokenHash, Instant now) {
        Condition active = ColumnLookups.matches(PANEL_LOGIN_TOKENS.TOKEN_HASH, tokenHash).and(PANEL_LOGIN_TOKENS.EXPIRES_AT.gt(now));
        return unitOfWork.write(() -> SingleUseRows.consume(dsl, PANEL_LOGIN_TOKENS, PANEL_LOGIN_TOKENS.ID, active))
            .map(JooqPanelLoginTokenRepository::toToken);
    }

    private static PanelLoginToken toToken(PanelLoginTokensRecord row) {
        return PanelLoginToken.builder()
            .id(row.getId())
            .tokenHash(row.getTokenHash())
            .identityEmail(row.getIdentityEmail())
            .minecraftUuid(row.getMinecraftUuid())
            .expiresAt(row.getExpiresAt())
            .createdAt(row.getCreatedAt())
            .build();
    }
}
