package gg.modl.backend.settings.data;

import java.util.Arrays;
import java.util.Optional;

public enum SettingsType {
    GENERAL("general"),
    PUNISHMENT_TYPES("punishmentTypes"),
    DELETED_PUNISHMENT_TYPES("deletedPunishmentTypes"),
    STATUS_THRESHOLDS("statusThresholds"),
    TICKET_FORMS("ticketForms"),
    QUICK_RESPONSES("quickResponses"),
    TICKET_LABELS("ticketLabels"),
    WEBHOOKS("webhookSettings"),
    AI_MODERATION("aiModerationSettings"),
    REPLAY_RETENTION("replayRetention"),
    REPLAYS("replaySettings"),
    DATA_RETENTION("dataRetention"),
    SIGN_IN("signIn"),
    MAIL("mail"),
    AI_PROVIDER("aiProvider"),
    AI_USAGE("aiUsage"),
    PLAYER_ADDRESSES("playerAddresses"),
    ADDRESS_FORWARDING("addressForwarding"),
    OPERATOR_LIMITS("operatorLimits"),
    STORAGE_BACKEND("storageBackend"),
    PUBLIC_THEME("publicTheme"),
    PUBLIC_CUSTOM_CSS("publicCustomCss"),
    PUBLIC_HOMEPAGE("publicHomepage"),
    HOSTED_IMPORT("hostedImport"),
    HOSTED_IMPORT_CHOICES("hostedImportChoices"),
    HOSTED_IMPORT_ALIASES("hostedImportAliases");

    private final String id;

    SettingsType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<SettingsType> ofId(String id) {
        return Arrays.stream(values()).filter(type -> type.id.equals(id)).findFirst();
    }
}
