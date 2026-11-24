package gg.modl.minecraft.core.impl.commands.staff;

import gg.modl.minecraft.api.http.request.PlayerLookupRequest;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.util.CommandUtil;
import gg.modl.minecraft.core.util.Pagination;
import revxrsal.commands.command.CommandActor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public abstract class AbstractLogCommand<T> {
    private static final int ENTRIES_PER_PAGE = 10;

    protected final HttpClientHolder httpClientHolder;
    protected final LocaleManager localeManager;

    protected AbstractLogCommand(HttpClientHolder httpClientHolder, LocaleManager localeManager) {
        this.httpClientHolder = httpClientHolder;
        this.localeManager = localeManager;
    }

    protected abstract String localePrefix();
    protected abstract CompletableFuture<List<T>> fetchEntries(String uuid);
    protected abstract long getTimestamp(T entry);
    protected abstract String getServer(T entry);
    protected abstract Map<String, String> entryPlaceholders(T entry, String playerName, String timestamp, String server);

    protected void execute(CommandActor actor, String playerQuery, String pageArg) {
        final int requestedPage = Pagination.parsePage(pageArg);
        String errorKey = localePrefix() + ".error";

        httpClientHolder.getClient().lookupPlayer(PlayerLookupRequest.builder().query(playerQuery).build()).thenAccept(response -> {
            if (response.isSuccess() && response.getData() != null) {
                String playerName = response.getData().getCurrentUsername();
                String targetUuid = response.getData().getMinecraftUuid();

                fetchEntries(targetUuid).thenAccept(entries -> displayEntries(actor, playerName, entries, requestedPage)).exceptionally(throwable -> {
                    CommandUtil.handleException(actor, throwable, localeManager, errorKey);
                    return null;
                });
            } else actor.reply(localeManager.getMessage("general.player_not_found"));
        }).exceptionally(throwable -> {
            CommandUtil.handleException(actor, throwable, localeManager, errorKey);
            return null;
        });
    }

    private void displayEntries(CommandActor actor, String playerName, List<T> entries, int page) {
        String header = localeManager.getMessage(localePrefix() + ".header", mapOf("player", playerName));
        if (header != null && !header.isEmpty()) actor.reply(header);

        if (entries == null || entries.isEmpty()) {
            actor.reply(localeManager.getMessage(localePrefix() + ".empty"));
            return;
        }

        Pagination.Page pg = PaginatedChatPrinter.printPage(entries, ENTRIES_PER_PAGE, page, (index, ordinal) -> {
            T entry = entries.get(index);
            String timestamp = localeManager.formatDate(Instant.ofEpochMilli(getTimestamp(entry)));
            String server = getServer(entry) != null ? getServer(entry) : localeManager.getUnknownWord();
            actor.reply(localeManager.getMessage(localePrefix() + ".entry",
                    entryPlaceholders(entry, playerName, timestamp, server)));
        });

        actor.reply(localeManager.getMessage(localePrefix() + ".footer", mapOf(
                "page", String.valueOf(pg.getPage()),
                "total_pages", String.valueOf(pg.getTotalPages()),
                "total", String.valueOf(entries.size())
        )));
    }
}
