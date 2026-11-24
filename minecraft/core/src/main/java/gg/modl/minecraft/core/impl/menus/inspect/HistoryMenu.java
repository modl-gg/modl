package gg.modl.minecraft.core.impl.menus.inspect;

import gg.modl.minecraft.core.PluginServices;
import dev.simplix.cirrus.actionhandler.ActionHandlers;
import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.api.Punishment;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.response.PunishmentTypesResponse;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.base.PaginatedInspectListMenu;
import gg.modl.minecraft.core.impl.menus.pagination.PaginatedDataSource.FetchResult;
import gg.modl.minecraft.core.impl.menus.util.InspectContext;
import gg.modl.minecraft.core.impl.menus.util.MenuAsync;
import gg.modl.minecraft.core.impl.menus.util.PunishmentItemRenderer;
import gg.modl.minecraft.core.impl.menus.util.ReportRenderUtil;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class HistoryMenu extends PaginatedInspectListMenu<Punishment> {
    private static final int PAGE_SIZE = 7;

    private final Map<Integer, PunishmentTypesResponse.PunishmentTypeData> typesByOrdinal = new HashMap<>();
    @Getter private final CompletableFuture<Void> dataFuture;

    public HistoryMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                       Account targetAccount, @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        this(platform, httpClient, viewerUuid, viewerName, targetAccount, backAction, null);
    }

    public HistoryMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                       Account targetAccount, @Nullable Consumer<CirrusPlayerWrapper> backAction, @Nullable InspectContext inspectContext) {
        super(ReportRenderUtil.playerTitle("menus.titles.history", targetAccount), platform, httpClient, viewerUuid, viewerName, targetAccount, backAction, inspectContext, PAGE_SIZE);

        this.dataFuture = loadPunishmentTypes();

        int totalCount = inspectContext != null ? inspectContext.punishmentCount() : targetAccount.getPunishments().size();
        List<Punishment> initial = new ArrayList<>(targetAccount.getPunishments());
        initial.sort(Comparator.comparing(Punishment::getIssued, Comparator.nullsLast(Comparator.reverseOrder())));
        dataSource.initialize(initial, totalCount);
    }

    private CompletableFuture<Void> loadPunishmentTypes() {
        return httpClient.getPunishmentTypes().thenAccept(response -> {
            if (response.isSuccess() && response.getData() != null) {
                for (PunishmentTypesResponse.PunishmentTypeData type : response.getData()) {
                    typesByOrdinal.put(type.getOrdinal(), type);
                }
            }
        }).exceptionally(e -> null);
    }

    @Override
    protected void openLoadedPage(Click click, int nextPage) {
        HistoryMenu newMenu = new HistoryMenu(platform, httpClient, viewerUuid, viewerName, targetAccount, backAction, inspectContext);
        newMenu.dataSource.initialize(dataSource.getAllLoadedItems(), dataSource.getTotalCount());
        newMenu.setInitialPage(nextPage);
        MenuAsync.displayWhenLoaded(platform, newMenu.getDataFuture(), click.player(), newMenu::display);
    }

    @Override
    protected CompletableFuture<FetchResult<Punishment>> fetchPage(int page, int limit) {
        return httpClient.getPlayerPunishments(targetUuid, page, limit)
                .thenApply(response -> response.getStatus() == 200
                        ? new FetchResult<>(response.getPunishments(), response.getTotalCount())
                        : FetchResult.<Punishment>failed())
                .exceptionally(e -> FetchResult.failed());
    }

    @Override
    protected CirrusItem emptyPlaceholder() {
        return createEmptyPlaceholder(PluginServices.locale().getMessage("menus.empty.history"));
    }

    @Override
    protected CirrusItem mapItem(Punishment punishment) {
        return PunishmentItemRenderer.render(punishment, getTypeName(punishment),
                "menus.history_item", Collections.emptyMap());
    }

    private String getTypeName(Punishment punishment) {
        int ordinal = punishment.getTypeOrdinal();
        PunishmentTypesResponse.PunishmentTypeData typeData = typesByOrdinal.get(ordinal);
        if (typeData != null && typeData.getName() != null) {
            return typeData.getName();
        }

        Object typeName = punishment.getDataMap().get("typeName");
        if (typeName instanceof String && !((String) typeName).isEmpty()) {
            return (String) typeName;
        }

        return punishment.getTypeCategory();
    }

    @Override
    protected void handleItemClick(Click click, Punishment punishment) {
        ActionHandlers.openMenu(
                new ModifyPunishmentMenu(platform, httpClient, viewerUuid, viewerName, targetAccount, punishment, backAction,
                        p -> {
                            HistoryMenu m = new HistoryMenu(platform, httpClient, viewerUuid, viewerName, targetAccount, backAction, inspectContext);
                            MenuAsync.displayWhenLoaded(platform, m.getDataFuture(), p, m::display);
                        }))
                .handle(click);
    }

    @Override
    protected void registerActionHandlers() {
        super.registerActionHandlers();

        registerActionHandler("openHistory", click -> {});
    }
}
