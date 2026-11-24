package gg.modl.minecraft.core.impl.menus.staff;

import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.PluginServices;
import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.Punishment;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.response.RecentPunishmentsResponse;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.base.BaseStaffListMenu;
import gg.modl.minecraft.core.impl.menus.util.PunishmentItemRenderer;
import gg.modl.minecraft.core.impl.menus.util.MenuAsync;
import gg.modl.minecraft.core.punishment.RecentPunishmentMapper;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;
import java.util.function.Consumer;

public class RecentPunishmentsMenu extends BaseStaffListMenu<RecentPunishmentsMenu.PunishmentWithPlayer> {
    @Value
    public static class PunishmentWithPlayer {
        Punishment punishment;
        @Nullable UUID playerUuid;
        String playerName;

        static PunishmentWithPlayer from(RecentPunishmentsResponse.RecentPunishment recent) {
            return new PunishmentWithPlayer(RecentPunishmentMapper.toPunishment(recent),
                    parseUuid(recent.getPlayerUuid()), recent.getPlayerName());
        }

        private static @Nullable UUID parseUuid(String value) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }
    }

    private final List<PunishmentWithPlayer> recentPunishments;
    @Getter private CompletableFuture<Void> dataFuture;

    public RecentPunishmentsMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                                  boolean isAdmin, PanelUrl panelUrl, @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        this(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction, null);
    }

    public RecentPunishmentsMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                                  boolean isAdmin, PanelUrl panelUrl, @Nullable Consumer<CirrusPlayerWrapper> backAction,
                                  @Nullable List<PunishmentWithPlayer> preloadedData) {
        super(PluginServices.locale().getMessage("menus.titles.recent_punishments"), platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction);
        this.recentPunishments = preloadedData != null ? new ArrayList<>(preloadedData) : new ArrayList<>();

        if (preloadedData == null)
            this.dataFuture = fetchRecentPunishments();
        else
            this.dataFuture = CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> fetchRecentPunishments() {
        return httpClient.getRecentPunishments(48).thenAccept(response -> {
            if (response.isSuccess() && response.getPunishments() != null) {
                recentPunishments.clear();
                for (RecentPunishmentsResponse.RecentPunishment p : response.getPunishments())
                    recentPunishments.add(PunishmentWithPlayer.from(p));
            }
        });
    }

    @Override
    protected Collection<PunishmentWithPlayer> items() {
        List<PunishmentWithPlayer> sorted = new ArrayList<>(recentPunishments);
        sorted.sort(Comparator.comparing((PunishmentWithPlayer p) -> p.getPunishment().getIssued(),
                Comparator.nullsLast(Comparator.reverseOrder())));
        return sorted;
    }

    @Override
    protected CirrusItem emptyPlaceholder() {
        return createEmptyPlaceholder(PluginServices.locale().getMessage("menus.empty.punishments"));
    }

    @Override
    protected CirrusItem mapItem(PunishmentWithPlayer pwp) {
        Punishment punishment = pwp.getPunishment();

        Object typeNameObj = punishment.getDataMap().get("typeName");
        String typeName = typeNameObj != null ? typeNameObj.toString() : punishment.getTypeCategory();

        Map<String, String> extraVars = mapOf("player", pwp.getPlayerName() != null ? pwp.getPlayerName() : localeManager().getUnknownWord());

        return PunishmentItemRenderer.render(punishment, typeName, "menus.recent_item", extraVars);
    }

    @Override
    protected void handleItemClick(Click click, PunishmentWithPlayer pwp) {
        List<PunishmentWithPlayer> currentData = new ArrayList<>(recentPunishments);
        Consumer<CirrusPlayerWrapper> returnToPunishments = p -> {
            RecentPunishmentsMenu m = new RecentPunishmentsMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, null, currentData);
            MenuAsync.displayWhenLoaded(platform, m.getDataFuture(), p, m::display);
        };

        UUID playerUuid = pwp.getPlayerUuid();
        click.clickedMenu().close();
        if (playerUuid == null) {
            sendMessage(localeManager().getMessage("menus.common.load_profile_failed"));
            return;
        }
        httpClient.getPlayerProfile(playerUuid).thenAccept(response -> {
            if (response.getStatus() == 200) {
                new StaffModifyPunishmentMenu(platform, httpClient, viewerUuid, viewerName,
                    response.getProfile(), pwp.getPunishment(), isAdmin, panelUrl, returnToPunishments)
                    .display(click.player());
            } else {
                sendMessage(localeManager().getMessage("menus.common.load_profile_failed"));
            }
        }).exceptionally(e -> {
            sendMessage(localeManager().getMessage("menus.common.load_profile_error", mapOf("error", String.valueOf(e.getMessage()))));
            return null;
        });
    }

    @Override
    protected void registerActionHandlers() {
        super.registerActionHandlers();

        registerActionHandler("openPunishments", click -> {});
    }
}
