package gg.modl.minecraft.core.impl.menus.staff;

import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.PluginServices;
import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.response.TicketsResponse;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.base.BaseStaffListMenu;
import gg.modl.minecraft.core.impl.menus.util.MenuItems;
import gg.modl.minecraft.core.impl.menus.util.BrowserLink;
import gg.modl.minecraft.core.impl.menus.util.MenuSlots;
import gg.modl.minecraft.core.impl.menus.util.ReportRenderUtil;
import gg.modl.minecraft.core.impl.menus.util.MenuAsync;
import gg.modl.minecraft.core.locale.LocaleManager;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class TicketsMenu extends BaseStaffListMenu<TicketsMenu.Ticket> {
    @Getter
    public static class Ticket {
        private final String id, playerName, title;
        private final @Nullable Instant created;
        private final String status;
        private final boolean hasStaffResponse;

        public Ticket(String id, String playerName, String title, @Nullable Instant created, String status,
                      boolean hasStaffResponse) {
            this.id = id;
            this.playerName = playerName;
            this.title = title;
            this.created = created;
            this.status = status;
            this.hasStaffResponse = hasStaffResponse;
        }
    }

    private final List<Ticket> tickets = new ArrayList<>();
    private String currentStatusFilter = "open";
    @Getter private CompletableFuture<Void> dataFuture;

    public TicketsMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                       boolean isAdmin, PanelUrl panelUrl, @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        super(PluginServices.locale().getMessage("menus.titles.support_tickets"), platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction);

        this.dataFuture = fetchTickets();
    }

    private CompletableFuture<Void> fetchTickets() {
        return httpClient.getTickets(null, null).thenAccept(response -> {
            if (response.isSuccess() && response.getTickets() != null) {
                tickets.clear();
                for (TicketsResponse.Ticket ticket : response.getTickets()) {
                    if ("Unfinished".equalsIgnoreCase(ticket.getStatus())) {
                        continue;
                    }
                    tickets.add(new Ticket(
                            ticket.getId(),
                            ticket.getPlayerName(),
                            ticket.getSubject(),
                            ticket.getCreatedAt(),
                            ticket.getStatus(),
                            ticket.isHasStaffResponse()
                    ));
                }
            }
        });
    }

    public TicketsMenu withStatusFilter(String statusFilter) {
        this.currentStatusFilter = statusFilter;
        return this;
    }

    @Override
    protected Map<Integer, CirrusItem> intercept(int menuSize) {
        Map<Integer, CirrusItem> items = super.intercept(menuSize);

        items.put(MenuSlots.FILTER_BUTTON, MenuItems.statusToggleButton(currentStatusFilter, "menus.common.items_tickets"));

        return items;
    }

    @Override
    protected Collection<Ticket> items() {
        List<Ticket> filtered = new ArrayList<>();

        for (Ticket ticket : tickets) {
            if (ReportRenderUtil.matchesStatusFilter(currentStatusFilter, ticket.getStatus()))
                filtered.add(ticket);
        }

        filtered.sort(Comparator.comparing(Ticket::getCreated, Comparator.nullsLast(Comparator.reverseOrder())));
        return filtered;
    }

    @Override
    protected CirrusItem emptyPlaceholder() {
        return createEmptyPlaceholder(PluginServices.locale().getMessage("menus.empty.tickets"));
    }

    @Override
    protected CirrusItem mapItem(Ticket ticket) {
        LocaleManager locale = PluginServices.locale();

        Map<String, String> vars = new HashMap<>();
        vars.put("id", ticket.getId());
        vars.put("player", ticket.getPlayerName() != null ? ticket.getPlayerName() : locale.getUnknownWord());
        vars.put("title", ticket.getTitle() != null ? ticket.getTitle() : locale.getMessage("menus.ticket_item.no_title"));
        vars.put("date", MenuItems.formatDate(ticket.getCreated()));
        vars.put("status", getStatusColor(ticket.getStatus()) + ticket.getStatus());

        List<String> lore = new ArrayList<>();
        for (String line : locale.getMessageList("menus.ticket_item.lore")) {
            String processed = line;
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                processed = processed.replace("{" + entry.getKey() + "}", entry.getValue());
            }
            lore.add(processed);
        }

        String title = locale.getMessage("menus.ticket_item.title", vars);

        CirrusItemType itemType = ticket.isHasStaffResponse() ? CirrusItemType.WRITABLE_BOOK : CirrusItemType.BOOK;

        return CirrusItem.of(
                itemType,
                CirrusChatElement.ofLegacyText(title),
                MenuItems.lore(lore)
        );
    }

    private String getStatusColor(String status) {
        if (status == null) return MenuItems.COLOR_GRAY;
        String lower = status.toLowerCase(Locale.ROOT);
        if ("open".equals(lower)) return MenuItems.COLOR_RED;
        if ("unfinished".equals(lower)) return MenuItems.COLOR_YELLOW;
        if ("closed".equals(lower)) return MenuItems.COLOR_GREEN;
        return MenuItems.COLOR_GRAY;
    }

    @Override
    protected void handleItemClick(Click click, Ticket ticket) {
        click.clickedMenu().close();

        platform.sendJsonMessage(viewerUuid, BrowserLink.ticketJson(ticket.getId(), panelUrl.staffTicket(ticket.getId())));
    }

    @Override
    protected void registerActionHandlers() {
        super.registerActionHandlers();

        registerActionHandler("filter", this::handleFilter);

        registerActionHandler("openTickets", click -> {});
    }

    private void handleFilter(Click click) {
        String newStatus = "open".equalsIgnoreCase(currentStatusFilter) ? "closed" : "open";
        TicketsMenu menu = new TicketsMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction)
                .withStatusFilter(newStatus);
        MenuAsync.displayWhenLoaded(platform, menu.getDataFuture(), click.player(), menu::display);
    }
}
