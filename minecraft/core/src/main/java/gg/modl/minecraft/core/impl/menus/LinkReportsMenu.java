package gg.modl.minecraft.core.impl.menus;

import gg.modl.minecraft.core.PluginServices;
import dev.simplix.cirrus.actionhandler.ActionHandlers;
import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import dev.simplix.cirrus.model.CallResult;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.base.ChromeListMenu;
import gg.modl.minecraft.core.impl.menus.base.MenuChrome;
import gg.modl.minecraft.core.impl.menus.util.MenuItems;
import gg.modl.minecraft.core.impl.menus.util.MenuSlots;
import gg.modl.minecraft.core.impl.menus.util.ReportRenderUtil;
import gg.modl.minecraft.core.impl.menus.util.ReportRenderUtil.LinkableReport;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public class LinkReportsMenu extends ChromeListMenu<LinkableReport> {
    private final Account targetAccount;
    private final List<LinkableReport> reports;
    private final Set<String> selectedReportIds;
    private String currentFilter = ReportRenderUtil.ALL_REPORT_TYPES;
    private final Consumer<Set<String>> onComplete;
    @Getter private final CompletableFuture<Void> dataFuture;

    public LinkReportsMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                           MenuChrome chrome, Account targetAccount, Set<String> preSelectedIds,
                           @Nullable Consumer<CirrusPlayerWrapper> backAction, Consumer<Set<String>> onComplete) {
        super(ReportRenderUtil.playerTitle("menus.titles.link_reports", targetAccount), platform, httpClient, viewerUuid, viewerName, backAction, chrome);
        this.targetAccount = targetAccount;
        this.selectedReportIds = new LinkedHashSet<>(preSelectedIds);
        this.onComplete = onComplete;
        this.reports = new ArrayList<>();
        this.dataFuture = ReportRenderUtil.loadLinkableReports(httpClient, targetAccount.getMinecraftUuid()).thenAccept(reports::addAll);
    }

    private LinkReportsMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                            MenuChrome chrome, Account targetAccount, Set<String> preSelectedIds,
                            @Nullable Consumer<CirrusPlayerWrapper> backAction, Consumer<Set<String>> onComplete,
                            List<LinkableReport> loadedReports) {
        super(ReportRenderUtil.playerTitle("menus.titles.link_reports", targetAccount), platform, httpClient, viewerUuid, viewerName, backAction, chrome);
        this.targetAccount = targetAccount;
        this.selectedReportIds = new LinkedHashSet<>(preSelectedIds);
        this.onComplete = onComplete;
        this.reports = new ArrayList<>(loadedReports);
        this.dataFuture = CompletableFuture.completedFuture(null);
    }

    public LinkReportsMenu withFilter(String filter) {
        this.currentFilter = filter;
        return this;
    }

    @Override
    protected Map<Integer, CirrusItem> intercept(int menuSize) {
        Map<Integer, CirrusItem> items = super.intercept(menuSize);

        items.put(MenuSlots.LINK_REPORTS_FILTER, MenuItems.filterButton(ReportRenderUtil.REPORT_TYPE_FILTERS, currentFilter));

        items.put(MenuSlots.LINK_REPORTS_APPLY, MenuItems.localized(CirrusItemType.OAK_SIGN,
                "menus.link_reports.apply", "menus.link_reports.apply_lore",
                mapOf("count", String.valueOf(selectedReportIds.size())))
                .actionHandler("applySelection"));

        items.put(MenuSlots.LINK_REPORTS_SELECT_ALL, MenuItems.localized(CirrusItemType.EMERALD,
                "menus.link_reports.select_all", "menus.link_reports.select_all_lore")
                .actionHandler("selectAll"));

        return items;
    }

    @Override
    protected Collection<LinkableReport> items() {
        return ReportRenderUtil.filterLinkableReports(reports, currentFilter);
    }

    @Override
    protected CirrusItem emptyPlaceholder() {
        return createEmptyPlaceholder(PluginServices.locale().getMessage("menus.empty.reports"));
    }

    @Override
    protected CirrusItem mapItem(LinkableReport report) {
        return ReportRenderUtil.mapLinkableReport(report, selectedReportIds, PluginServices.locale());
    }

    @Override
    protected void handleItemClick(Click click, LinkableReport report) {
        ReportRenderUtil.toggleReportSelection(selectedReportIds, report.getId());

        ActionHandlers.openMenu(rebuild()).handle(click);
    }

    @Override
    protected void registerActionHandlers() {
        super.registerActionHandlers();

        registerActionHandler("filter", this::handleFilter);

        registerActionHandler("applySelection", click -> {
            handleApply(click);
            return CallResult.DENY_GRABBING;
        });

        registerActionHandler("selectAll", click -> {
            handleSelectAll(click);
            return CallResult.DENY_GRABBING;
        });

        for (LinkableReport report : reports) {
            registerActionHandler("toggleReport_" + report.getId(), click -> {
                handleItemClick(click, report);
                return CallResult.DENY_GRABBING;
            });
        }
    }

    private LinkReportsMenu rebuild() {
        return new LinkReportsMenu(platform, httpClient, viewerUuid, viewerName,
                chrome, targetAccount, selectedReportIds, backAction, onComplete, reports).withFilter(currentFilter);
    }

    private void handleFilter(Click click) {
        String newFilter = ReportRenderUtil.REPORT_TYPE_FILTERS.next(currentFilter);

        ActionHandlers.openMenu(rebuild().withFilter(newFilter)).handle(click);
    }

    private void handleApply(Click click) {
        click.clickedMenu().close();
        onComplete.accept(selectedReportIds);
    }

    private void handleSelectAll(Click click) {
        List<LinkableReport> filtered = ReportRenderUtil.filterLinkableReports(reports, currentFilter);
        ReportRenderUtil.toggleFilteredReportSelection(selectedReportIds, filtered);

        ActionHandlers.openMenu(rebuild()).handle(click);
    }
}
