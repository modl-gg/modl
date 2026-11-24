package gg.modl.backend.auth.repository;

import gg.modl.backend.support.sql.SqlTestDatabase;

public record AuthRepositories(
    AuthCodeRepository authCodes,
    PanelSessionRepository sessions,
    WebAuthnCredentialRepository credentials,
    WebAuthnChallengeRepository challenges,
    PanelLoginTokenRepository loginTokens,
    MinecraftLinkCodeRepository linkCodes,
    StaffCredentialRepository passwords,
    StaffTotpRepository totp,
    PasswordResetTokenRepository resetTokens
) {
    public static AuthRepositories on(SqlTestDatabase database) {
        return new AuthRepositories(
            new JooqAuthCodeRepository(database.dsl(), database.unitOfWork(), database.insertIfAbsent(), database.dialect(),
                database.clock()),
            new JooqPanelSessionRepository(database.dsl(), database.unitOfWork(), database.clock()),
            new JooqWebAuthnCredentialRepository(database.dsl(), database.unitOfWork(), database.insertIfAbsent()),
            new JooqWebAuthnChallengeRepository(database.dsl(), database.unitOfWork()),
            new JooqPanelLoginTokenRepository(database.dsl(), database.unitOfWork()),
            new JooqMinecraftLinkCodeRepository(database.dsl(), database.unitOfWork(), database.insertIfAbsent()),
            new JooqStaffCredentialRepository(database.dsl(), database.unitOfWork(), database.insertIfAbsent(), database.upserts()),
            new JooqStaffTotpRepository(database.dsl(), database.unitOfWork(), database.upserts()),
            new JooqPasswordResetTokenRepository(database.dsl(), database.unitOfWork()));
    }
}
