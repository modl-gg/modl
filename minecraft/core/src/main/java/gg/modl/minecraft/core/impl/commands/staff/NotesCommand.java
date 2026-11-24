package gg.modl.minecraft.core.impl.commands.staff;

import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.api.Note;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.command.RequiresPlayerView;
import gg.modl.minecraft.core.command.StaffOnly;
import gg.modl.minecraft.core.impl.menus.inspect.NotesMenu;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.util.CommandUtil;
import gg.modl.minecraft.core.util.NoteDisplay;
import gg.modl.minecraft.core.util.Pagination;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.command.CommandActor;

import java.util.List;
import java.util.UUID;
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@RequiredArgsConstructor
public class NotesCommand {
    private final HttpClientHolder httpClientHolder;
    private final Platform platform;
    private final Cache cache;
    private final LocaleManager localeManager;

    @Command("notes")
    @Description("Open the notes menu for a player, or use -p to print to chat")
    @StaffOnly
    @RequiresPlayerView
    public void notes(CommandActor actor, @Named("player") String playerQuery, @Optional @Nullable String flags) {
        if (flags == null) flags = "";
        int page = Pagination.parsePrintFlags(flags);
        boolean printMode = page > 0;

        if (CommandUtil.rendersAsText(actor, platform, printMode)) {
            printNotes(actor, playerQuery, Math.max(1, page));
            return;
        }

        UUID senderUuid = actor.uniqueId();
        ProfileMenuOpener.openProfileMenu(actor, httpClientHolder.getClient(), platform, cache, localeManager, playerQuery,
                (profileResponse, senderName, viewer) -> new NotesMenu(
                    platform, httpClientHolder.getClient(), senderUuid, senderName,
                    profileResponse.getProfile(), null
                ).display(viewer));
    }

    private void printNotes(CommandActor actor, String playerQuery, int page) {

        StaffProfileLookup.lookupPlayerProfile(httpClientHolder.getClient(), platform, playerQuery).thenAccept(profileResponse -> {
            if (profileResponse.getStatus() == 200) {
                Account profile = profileResponse.getProfile();
                List<Account.Username> usernames = profile.getUsernames();
                String playerName = !usernames.isEmpty() ? usernames.get(usernames.size() - 1).getUsername() : playerQuery;
                displayNotes(actor, playerName, profile, page);
            } else actor.reply(localeManager.getMessage("general.player_not_found"));
        }).exceptionally(throwable -> {
            CommandUtil.handleException(actor, throwable, localeManager);
            return null;
        });
    }

    private static final int ENTRIES_PER_PAGE = 8;

    private void displayNotes(CommandActor actor, String playerName, Account profile, int page) {
        List<Note> notes = profile.getNotes();
        actor.reply(localeManager.getMessage("print.notes.header", mapOf("player", playerName)));

        if (notes.isEmpty()) actor.reply(localeManager.getMessage("print.notes.empty"));
        else PaginatedChatPrinter.printPageWithTotal(actor, localeManager, notes, ENTRIES_PER_PAGE, page,
                "print.notes.total", (index, ordinal) -> {
                    Note note = notes.get(index);
                    actor.reply(localeManager.getMessage("print.notes.entry", mapOf(
                            "ordinal", String.valueOf(ordinal),
                            "date", formatNoteDate(note),
                            "author", note.issuerNameOr(localeManager.getUnknownWord()),
                            "content", NoteDisplay.text(localeManager, note)
                    )));
                });

        actor.reply(localeManager.getMessage("print.notes.footer"));
    }

    private String formatNoteDate(Note note) {
        return localeManager.formatDate(note.getDate());
    }

}

