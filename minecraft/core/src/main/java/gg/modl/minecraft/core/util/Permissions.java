package gg.modl.minecraft.core.util;

import gg.modl.minecraft.api.PunishmentTypeClassifier;

public final class Permissions {
    private Permissions() {}

    public static final String ADMIN = "modl.admin",
            MOD_ACTIONS = "staff.modactions",
            CHAT_LOGS = "staff.chatlogs",
            COMMAND_LOGS = "staff.commandlogs",
            INTERCEPT = "staff.intercept",
            SETTINGS_VIEW = "admin.settings.view",
            SETTINGS_MODIFY = "admin.settings.modify",
            STAFF_MANAGE = "admin.staff.manage",
            STAFF_MANAGE_MEMBERS = "admin.staff.manage.members",
            STAFF_MANAGE_ROLES = "admin.staff.manage.roles",
            PUNISHMENT_VIEW = "punishment.view",
            PUNISHMENT_MODIFY = "punishment.modify",
            PUNISHMENT_MODIFY_PARDON = "punishment.modify.pardon",
            PUNISHMENT_MODIFY_DURATION = "punishment.modify.duration",
            PUNISHMENT_MODIFY_NOTE = "punishment.modify.note",
            PUNISHMENT_MODIFY_EVIDENCE = "punishment.modify.evidence",
            PUNISHMENT_MODIFY_OPTIONS = "punishment.modify.options",
            PUNISHMENT_MODIFY_TICKETS = "punishment.modify.tickets",
            PUNISHMENT_APPLY_PREFIX = "punishment.apply.",
            PUNISHMENT_APPLY_MANUAL_BAN = PUNISHMENT_APPLY_PREFIX + PunishmentTypeClassifier.ORDINAL_BAN,
            PUNISHMENT_APPLY_MANUAL_MUTE = PUNISHMENT_APPLY_PREFIX + PunishmentTypeClassifier.ORDINAL_MUTE,
            PUNISHMENT_APPLY_KICK = PUNISHMENT_APPLY_PREFIX + PunishmentTypeClassifier.ORDINAL_KICK,
            PUNISHMENT_APPLY_BLACKLIST = PUNISHMENT_APPLY_PREFIX + PunishmentTypeClassifier.ORDINAL_BLACKLIST,
            PLAYER_NOTE = "player.note",
            PLAYER_WARN = "player.warn",
            TICKET_VIEW_ALL = "ticket.view.all",
            TICKET_VIEW_ALL_NOTES = "ticket.view.all.notes",
            TICKET_REPLY_ALL = "ticket.reply.all",
            TICKET_REPLY_ALL_NOTES = "ticket.reply.all.notes",
            TICKET_CLOSE_ALL = "ticket.close.all",
            TICKET_MANAGE = "ticket.manage",
            TICKET_MANAGE_TAGS = "ticket.manage.tags";
}
