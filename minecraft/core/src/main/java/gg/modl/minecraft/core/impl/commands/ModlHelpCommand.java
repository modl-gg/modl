package gg.modl.minecraft.core.impl.commands;

import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Default;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.command.CommandActor;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.plugin.PluginInfo;
import gg.modl.minecraft.core.util.CommandUtil;
import gg.modl.minecraft.core.util.Pagination;
import gg.modl.minecraft.core.staff.PermissionUtil;
import gg.modl.minecraft.core.util.Permissions;
import lombok.RequiredArgsConstructor;
import lombok.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@RequiredArgsConstructor @Command("modl")
public class ModlHelpCommand {
    private static final int ENTRIES_PER_PAGE = 8;

    private final Cache cache;
    private final LocaleManager localeManager;
    private final Predicate<CommandActor> localConsole;

    @Description("")
    public void showHelp(CommandActor actor) {
        actor.reply(localeManager.getMessage("help.version_header", mapOf("version", PluginInfo.VERSION)));
        actor.reply(localeManager.getMessage("help.version_footer"));
    }

    @Subcommand("help")
    @Description("Show available modl commands")
    public void help(CommandActor actor, @Default("1") String pageArg) {
        displayHelp(actor, Pagination.parsePage(pageArg));
    }

    private void displayHelp(CommandActor actor, int page) {
        UUID senderUuid = actor.uniqueId();
        boolean isConsole = CommandUtil.isConsole(senderUuid);
        boolean isStaff = !isConsole && PermissionUtil.isStaff(senderUuid, cache);
        boolean isAdmin = !isConsole && cache.hasPermission(senderUuid, Permissions.ADMIN);

        List<HelpEntry> entries = new ArrayList<>();

        addEntry(entries, "player_commands.iammuted");
        addEntry(entries, "player_commands.standing");
        addEntry(entries, "player_commands.report");
        addEntry(entries, "player_commands.chatreport");
        addEntry(entries, "player_commands.claimticket");
        addEntry(entries, "player_commands.apply");
        addEntry(entries, "player_commands.bugreport");
        addEntry(entries, "player_commands.support");
        addEntry(entries, "player_commands.link");
        addEntry(entries, "player_commands.panel");

        if (isStaff || isConsole) {
            addEntry(entries, "staff_commands.staffmenu");
            if (isConsole || PermissionUtil.canViewPlayers(senderUuid, cache)) {
                addEntry(entries, "staff_commands.inspect");
                addEntry(entries, "staff_commands.history");
                addEntry(entries, "staff_commands.notes");
                addEntry(entries, "staff_commands.alts");
            }
            addEntry(entries, "staff_commands.reports");
            addEntry(entries, "staff_commands.punish");

            if (visibleWith(senderUuid, Permissions.PUNISHMENT_APPLY_MANUAL_BAN)) {
                addEntry(entries, "staff_commands.ban");
            }

            if (visibleWith(senderUuid, Permissions.PUNISHMENT_APPLY_MANUAL_MUTE)) {
                addEntry(entries, "staff_commands.mute");
            }

            if (visibleWith(senderUuid, Permissions.PUNISHMENT_APPLY_KICK)) {
                addEntry(entries, "staff_commands.kick");
            }

            if (visibleWith(senderUuid, Permissions.PUNISHMENT_APPLY_BLACKLIST)) {
                addEntry(entries, "staff_commands.blacklist");
            }

            if (visibleWith(senderUuid, Permissions.PUNISHMENT_MODIFY_PARDON)) {
                addEntry(entries, "staff_commands.pardon");
                addEntry(entries, "staff_commands.unban");
                addEntry(entries, "staff_commands.unmute");
            }

            addEntry(entries, "staff_commands.replay");

            if (isAdmin || isConsole) {
                addEntry(entries, "staff_commands.modl_reload");
            }

            if (localConsole.test(actor)) {
                addEntry(entries, "staff_commands.modl_import");
                addEntry(entries, "staff_commands.modl_status");
            }
        }

        Pagination.Page pg = Pagination.paginate(entries, ENTRIES_PER_PAGE, page);
        if (pg.isOutOfRange()) {
            actor.reply(localeManager.getMessage("help.no_more_pages"));
            return;
        }

        actor.reply("");
        actor.reply(localeManager.getMessage("help.header", mapOf(
                "version", PluginInfo.VERSION,
                "page", String.valueOf(pg.getPage()),
                "total_pages", String.valueOf(pg.getTotalPages())
        )));

        for (int i = pg.getStart(); i < pg.getEnd(); i++) {
            HelpEntry entry = entries.get(i);
            actor.reply(localeManager.getMessage("help.entry", mapOf(
                    "command", entry.getCommand(),
                    "description", entry.getDescription()
            )));
        }

        if (pg.hasNextPage())
            actor.reply(localeManager.getMessage("help.footer", mapOf(
                    "page", String.valueOf(pg.getPage()),
                    "total_pages", String.valueOf(pg.getTotalPages()),
                    "next_page", String.valueOf(pg.getPage() + 1)
            )));
        actor.reply("");
    }

    private boolean visibleWith(UUID senderUuid, String permission) {
        return CommandUtil.isConsole(senderUuid) || cache.hasPermission(senderUuid, permission);
    }

    private void addEntry(List<HelpEntry> entries, String localeKey) {
        String usageKey = "help." + localeKey + ".usage";
        if (!localeManager.hasMessage(usageKey)) return;
        String usage = localeManager.getMessage(usageKey);
        if (usage.isEmpty()) return;
        String description = localeManager.getMessage("help." + localeKey + ".description");
        entries.add(new HelpEntry(usage, description));
    }

    @Value
    private static class HelpEntry {
        String command;
        String description;
    }
}
