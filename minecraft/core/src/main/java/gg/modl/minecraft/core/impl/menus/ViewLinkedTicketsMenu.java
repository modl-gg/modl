package gg.modl.minecraft.core.impl.menus;

import gg.modl.minecraft.core.PluginServices;
import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.response.TicketsResponse;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.base.ChromeListMenu;
import gg.modl.minecraft.core.impl.menus.base.MenuChrome;
import gg.modl.minecraft.core.impl.menus.util.LinkedTicketItems;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class ViewLinkedTicketsMenu extends ChromeListMenu<TicketsResponse.Ticket> {
    private final List<TicketsResponse.Ticket> tickets = new ArrayList<>();
    @Getter private final CompletableFuture<Void> dataFuture;

    public ViewLinkedTicketsMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                                 MenuChrome chrome, List<String> ticketIds, @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        super(PluginServices.locale().getMessage("menus.titles.linked_tickets"), platform, httpClient, viewerUuid, viewerName, backAction, chrome);
        this.dataFuture = LinkedTicketItems.loadTickets(httpClient, ticketIds).thenAccept(tickets::addAll);
    }

    @Override
    protected Collection<TicketsResponse.Ticket> items() {
        return tickets;
    }

    @Override
    protected CirrusItem emptyPlaceholder() {
        return createEmptyPlaceholder(localeManager().getMessage("menus.empty.linked_tickets"));
    }

    @Override
    protected CirrusItem mapItem(TicketsResponse.Ticket ticket) {
        return LinkedTicketItems.mapTicket(ticket, platform);
    }

    @Override
    protected void handleItemClick(Click click, TicketsResponse.Ticket ticket) {
        LinkedTicketItems.handleTicketClick(click, ticket, platform, viewerUuid);
    }
}
