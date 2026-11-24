package gg.modl.backend.database;

import static gg.modl.backend.database.jooq.Tables.AUTH_CODES;
import static gg.modl.backend.database.jooq.Tables.EVIDENCE_UPLOAD_TOKENS;
import static gg.modl.backend.database.jooq.Tables.INVITATIONS;
import static gg.modl.backend.database.jooq.Tables.MINECRAFT_LINK_CODES;
import static gg.modl.backend.database.jooq.Tables.PANEL_LOGIN_TOKENS;
import static gg.modl.backend.database.jooq.Tables.PANEL_SESSIONS;
import static gg.modl.backend.database.jooq.Tables.PASSWORD_RESET_TOKENS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTIFICATIONS;
import static gg.modl.backend.database.jooq.Tables.TICKET_VERIFICATIONS;
import static gg.modl.backend.database.jooq.Tables.WEBAUTHN_CHALLENGES;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ExpiringRowsConfiguration {
    @Bean
    ExpiringRows expiringInvitations() {
        return new ExpiringRows(INVITATIONS, INVITATIONS.EXPIRES_AT);
    }

    @Bean
    ExpiringRows expiringTicketVerifications() {
        return new ExpiringRows(TICKET_VERIFICATIONS, TICKET_VERIFICATIONS.EXPIRES_AT);
    }

    @Bean
    ExpiringRows expiringWebAuthnChallenges() {
        return new ExpiringRows(WEBAUTHN_CHALLENGES, WEBAUTHN_CHALLENGES.EXPIRES_AT);
    }

    @Bean
    ExpiringRows expiringPanelSessions() {
        return new ExpiringRows(PANEL_SESSIONS, PANEL_SESSIONS.EXPIRES_AT);
    }

    @Bean
    ExpiringRows expiringPasswordResetTokens() {
        return new ExpiringRows(PASSWORD_RESET_TOKENS, PASSWORD_RESET_TOKENS.EXPIRES_AT);
    }

    @Bean
    ExpiringRows expiringAuthCodes() {
        return new ExpiringRows(AUTH_CODES, AUTH_CODES.EXPIRES_AT);
    }

    @Bean
    ExpiringRows expiringPanelLoginTokens() {
        return new ExpiringRows(PANEL_LOGIN_TOKENS, PANEL_LOGIN_TOKENS.EXPIRES_AT);
    }

    @Bean
    ExpiringRows expiringMinecraftLinkCodes() {
        return new ExpiringRows(MINECRAFT_LINK_CODES, MINECRAFT_LINK_CODES.EXPIRES_AT);
    }

    @Bean
    ExpiringRows expiringEvidenceUploadTokens() {
        return new ExpiringRows(EVIDENCE_UPLOAD_TOKENS, EVIDENCE_UPLOAD_TOKENS.EXPIRES_AT);
    }

    @Bean
    ExpiringRows expiringPlayerNotifications() {
        return new ExpiringRows(PLAYER_NOTIFICATIONS, PLAYER_NOTIFICATIONS.EXPIRES_AT);
    }
}
