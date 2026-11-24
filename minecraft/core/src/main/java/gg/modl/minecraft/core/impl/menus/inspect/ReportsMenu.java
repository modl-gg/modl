package gg.modl.minecraft.core.impl.menus.inspect;

import gg.modl.minecraft.core.PluginServices;
import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import dev.simplix.cirrus.model.CirrusClickType;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.response.ReportsResponse;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.base.BaseInspectListMenu;
import gg.modl.minecraft.core.impl.menus.util.MenuAsync;
import gg.modl.minecraft.core.impl.menus.util.MenuItems;
import gg.modl.minecraft.core.impl.menus.util.MenuSlots;
import gg.modl.minecraft.core.impl.menus.util.ReportDismissal;
import gg.modl.minecraft.core.impl.menus.util.ReportRenderUtil;
import gg.modl.minecraft.core.locale.LocaleManager;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;


public class ReportsMenu extends BaseInspectListMenu<ReportsMenu.Report> {
    @Getter
    public static class Report {
        private final String id, type, reporterName, content, status;
        private final @Nullable Instant date;

        public Report(String id, String type, String reporterName, String content, String status, @Nullable Instant date) {
            this.id = id;
            this.type = type;
            this.reporterName = reporterName;
            this.content = content;
            this.status = status;
            this.date = date;
        }

    }

    private final List<Report> reports = new ArrayList<>();
    private String currentFilter = ReportRenderUtil.ALL_REPORT_TYPES, currentStatusFilter = "open";

    @Getter private final CompletableFuture<Void> dataFuture;

    public ReportsMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                       Account targetAccount, @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        super(ReportRenderUtil.playerTitle("menus.titles.reports", targetAccount), platform, httpClient, viewerUuid, viewerName, targetAccount, backAction);

        this.dataFuture = fetchReports();
    }

    private CompletableFuture<Void> fetchReports() {
        return httpClient.getPlayerReports(targetAccount.getMinecraftUuid(), "all").thenAccept(response -> {
            if (response.isSuccess() && response.getReports() != null) {
                reports.clear();
                for (ReportsResponse.Report report : response.getReports()) {
                    reports.add(new Report(
                            report.getId(),
                            ReportRenderUtil.normalizeReportType(report),
                            report.getReporterName(),
                            ReportRenderUtil.getReportContent(report),
                            report.getStatus(),
                            report.getCreatedAt()
                    ));
                }
            }
        });
    }

    public ReportsMenu withFilter(String filter) {
        this.currentFilter = filter;
        return this;
    }

    public ReportsMenu withStatusFilter(String statusFilter) {
        this.currentStatusFilter = statusFilter;
        return this;
    }

    @Override
    protected Map<Integer, CirrusItem> intercept(int menuSize) {
        Map<Integer, CirrusItem> items = super.intercept(menuSize);

        items.put(MenuSlots.FILTER_BUTTON, MenuItems.filterButton(ReportRenderUtil.REPORT_TYPE_FILTERS, currentFilter, currentStatusFilter, "menus.common.items_reports"));

        return items;
    }

    @Override
    protected Collection<Report> items() {
        List<Report> filtered = new ArrayList<>();
        for (Report report : reports) {
            boolean typeMatch = ReportRenderUtil.ALL_REPORT_TYPES.equals(currentFilter) || (report.getType() != null && report.getType().equalsIgnoreCase(currentFilter));
            boolean statusMatch = ReportRenderUtil.matchesStatusFilter(currentStatusFilter, report.getStatus());
            if (typeMatch && statusMatch)
                filtered.add(report);
        }

        filtered.sort(Comparator.comparing(Report::getDate, Comparator.nullsLast(Comparator.reverseOrder())));
        return filtered;
    }

    @Override
    protected CirrusItem emptyPlaceholder() {
        return createEmptyPlaceholder(PluginServices.locale().getMessage("menus.empty.reports"));
    }

    @Override
    protected CirrusItem mapItem(Report report) {
        LocaleManager locale = PluginServices.locale();

        Map<String, String> vars = new HashMap<>();
        vars.put("id", report.getId());
        vars.put("type", report.getType() != null ? report.getType() : locale.getUnknownWord());
        vars.put("date", MenuItems.formatDate(report.getDate()));
        vars.put("reporter", report.getReporterName() != null ? report.getReporterName() : locale.getUnknownWord());
        vars.put("content", String.join("\n", ReportRenderUtil.processContent(report.getContent())));
        vars.put("reported", targetName);
        vars.put("status", ReportRenderUtil.statusLabel(locale, report.getStatus()));

        List<String> lore = ReportRenderUtil.buildLore(locale, "menus.report_item.lore", vars);
        String title = locale.getMessage("menus.report_item.title", vars);
        CirrusItemType itemType = ReportRenderUtil.getReportItemType(report.getType());

        return CirrusItem.of(
                itemType,
                CirrusChatElement.ofLegacyText(title),
                MenuItems.lore(lore)
        );
    }

    @Override
    protected void handleItemClick(Click click, Report report) {
        if (click.clickType().equals(CirrusClickType.RIGHT_CLICK))
            dismissReport(click, report);
    }

    private void dismissReport(Click click, Report report) {
        ReportDismissal.dismiss(platform, httpClient, viewerUuid, viewerName, report.getId(), () -> {
            ReportsMenu refreshed = new ReportsMenu(platform, httpClient, viewerUuid, viewerName, targetAccount, backAction)
                    .withFilter(currentFilter).withStatusFilter(currentStatusFilter);
            MenuAsync.displayWhenLoaded(platform, refreshed.getDataFuture(), click.player(), refreshed::display);
        });
    }

    @Override
    protected void registerActionHandlers() {
        super.registerActionHandlers();

        registerActionHandler("filter", this::handleFilter);
        registerActionHandler("openReports", click -> {});
    }

    private void handleFilter(Click click) {
        if (click.clickType().equals(CirrusClickType.RIGHT_CLICK)) {
            String newStatus = "open".equalsIgnoreCase(currentStatusFilter) ? "closed" : "open";
            ReportsMenu refreshed = new ReportsMenu(platform, httpClient, viewerUuid, viewerName, targetAccount, backAction)
                    .withFilter(currentFilter)
                    .withStatusFilter(newStatus);
            MenuAsync.displayWhenLoaded(platform, refreshed.getDataFuture(), click.player(), refreshed::display);
        } else {
            String newFilter = ReportRenderUtil.REPORT_TYPE_FILTERS.next(currentFilter);

            ReportsMenu refreshed = new ReportsMenu(platform, httpClient, viewerUuid, viewerName, targetAccount, backAction)
                    .withFilter(newFilter)
                    .withStatusFilter(currentStatusFilter);
            MenuAsync.displayWhenLoaded(platform, refreshed.getDataFuture(), click.player(), refreshed::display);
        }
    }
}
