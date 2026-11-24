package gg.modl.minecraft.core.impl.menus.inspect;

import gg.modl.minecraft.core.impl.menus.util.MenuAsync;
import gg.modl.minecraft.api.PlayerNoteKind;
import gg.modl.minecraft.core.PluginServices;
import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.api.Note;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.request.CreatePlayerNoteRequest;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.base.PaginatedInspectListMenu;
import gg.modl.minecraft.core.impl.menus.pagination.PaginatedDataSource.FetchResult;
import gg.modl.minecraft.core.impl.menus.util.InspectContext;
import gg.modl.minecraft.core.impl.menus.util.MenuItems;
import gg.modl.minecraft.core.impl.menus.util.MenuSlots;
import gg.modl.minecraft.core.impl.menus.util.ReportRenderUtil;
import gg.modl.minecraft.core.impl.menus.util.StaffViewer;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.staff.action.StaffRequirements;
import gg.modl.minecraft.core.util.ApiFailures;
import gg.modl.minecraft.core.util.ClickableJsonMessage;
import gg.modl.minecraft.core.util.NoteDisplay;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class NotesMenu extends PaginatedInspectListMenu<Note> {
    private static final int PAGE_SIZE = 7;

    public NotesMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                     Account targetAccount, @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        this(platform, httpClient, viewerUuid, viewerName, targetAccount, backAction, null);
    }

    public NotesMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                     Account targetAccount, @Nullable Consumer<CirrusPlayerWrapper> backAction, @Nullable InspectContext inspectContext) {
        super(ReportRenderUtil.playerTitle("menus.titles.notes", targetAccount), platform, httpClient, viewerUuid, viewerName, targetAccount, backAction, inspectContext, PAGE_SIZE);

        int totalCount = inspectContext != null ? inspectContext.noteCount() : targetAccount.getNotes().size();
        List<Note> initial = new ArrayList<>(targetAccount.getNotes());
        initial.sort(Comparator.comparing(Note::getDate, Comparator.nullsLast(Comparator.reverseOrder())));
        dataSource.initialize(initial, totalCount);
    }

    @Override
    protected Map<Integer, CirrusItem> intercept(int menuSize) {
        Map<Integer, CirrusItem> items = super.intercept(menuSize);

        if (new StaffViewer(platform, viewerUuid).allows(StaffRequirements.ADD_PLAYER_NOTE)) {
            items.put(MenuSlots.CREATE_NOTE_BUTTON, MenuItems.localized(CirrusItemType.OAK_SIGN,
                    "menus.notes.create", "menus.notes.create_lore", mapOf("player", targetName))
                    .actionHandler("createNote"));
        }

        return items;
    }

    @Override
    protected void openLoadedPage(Click click, int nextPage) {
        NotesMenu newMenu = new NotesMenu(platform, httpClient, viewerUuid, viewerName, targetAccount, backAction, inspectContext);
        newMenu.dataSource.initialize(dataSource.getAllLoadedItems(), dataSource.getTotalCount());
        newMenu.setInitialPage(nextPage);
        newMenu.display(click.player());
    }

    @Override
    protected CompletableFuture<FetchResult<Note>> fetchPage(int page, int limit) {
        return httpClient.getPlayerNotes(targetUuid, page, limit)
                .thenApply(response -> response.getStatus() == 200
                        ? new FetchResult<>(response.getNotes(), response.getTotalCount())
                        : FetchResult.<Note>failed())
                .exceptionally(e -> FetchResult.failed());
    }

    @Override
    protected CirrusItem emptyPlaceholder() {
        return createEmptyPlaceholder(PluginServices.locale().getMessage("menus.empty.notes"));
    }

    @Override
    protected CirrusItem mapItem(Note note) {
        LocaleManager locale = PluginServices.locale();

        String formattedDate = MenuItems.formatDate(note.getDate());
        String author = note.issuerNameOr("");
        String content = NoteDisplay.text(locale, note);

        Map<String, String> vars = mapOf(
                "date", formattedDate,
                "author", author,
                "content", content
        );

        List<String> lore = new ArrayList<>();
        for (String line : locale.getMessageList("menus.note_item.lore")) {
            String processed = line;
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                processed = processed.replace("{" + entry.getKey() + "}", entry.getValue());
            }
            lore.add(processed);
        }

        String title = locale.getMessage("menus.note_item.title", vars);

        return CirrusItem.of(
                CirrusItemType.PAPER,
                CirrusChatElement.ofLegacyText(title),
                MenuItems.lore(lore)
        );
    }

    @Override
    protected void handleItemClick(Click click, Note note) {
        String noteText = note.getText();
        String preview = noteText.substring(0, Math.min(30, noteText.length())) +
                (noteText.length() > 30 ? "..." : "");
        String json = ClickableJsonMessage.empty()
                .suggestCommand(noteText)
                .extra(ClickableJsonMessage.text(localeManager().getMessage("menus.notes.chat_label")).color("gray"))
                .extra(ClickableJsonMessage.text(preview).color("white"))
                .toJson();

        platform.sendJsonMessage(viewerUuid, json);
    }

    @Override
    protected void registerActionHandlers() {
        super.registerActionHandlers();

        registerActionHandler("createNote", this::handleCreateNote);
        registerActionHandler("openNotes", click -> {});
    }

    private void handleCreateNote(Click click) {
        new StaffViewer(platform, viewerUuid).promptFromMenu(click, StaffRequirements.ADD_PLAYER_NOTE,
                localeManager().getMessage("menus.notes.prompt", mapOf("player", targetName)),
                (issuer, input) -> {
                    CreatePlayerNoteRequest request = new CreatePlayerNoteRequest(
                            targetUuid.toString(),
                            viewerName,
                            issuer.actionIssuer(),
                            PlayerNoteKind.NOTE,
                            input
                    );

                    httpClient.createPlayerNote(request).thenAccept(v -> {
                        sendMessage(PluginServices.locale().getMessage("menus.notes.created"));
                        MenuAsync.afterSave(platform, viewerUuid, httpClient.getPlayerProfile(targetUuid), response -> {
                            if (response.getStatus() != 200) {
                                throw new IllegalStateException("The profile refresh answered " + response.getStatus());
                            }
                            InspectContext newContext = new InspectContext(response.getProfile(),
                                    inspectContext != null ? inspectContext.punishmentCount() : response.getPunishmentCount(),
                                    response.getNoteCount());
                            new NotesMenu(platform, httpClient, viewerUuid, viewerName,
                                response.getProfile(), backAction, newContext)
                                .display(click.player());
                        });
                    }).exceptionally(e -> {
                        sendMessage(ApiFailures.describe(PluginServices.locale(), "menus.notes.create_failed", e));
                        return null;
                    });
                },
                () -> {
                    sendMessage(PluginServices.locale().getMessage("menus.notes.cancelled"));
                    display(click.player());
                }
        );
    }
}
