package gg.modl.minecraft.core.impl.menus.staff;

import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.PluginServices;
import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import dev.simplix.cirrus.model.CirrusClickType;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.response.ReportsResponse;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.base.BaseStaffListMenu;
import gg.modl.minecraft.core.impl.menus.inspect.InspectMenu;
import gg.modl.minecraft.core.impl.menus.util.MenuItems;
import gg.modl.minecraft.core.impl.menus.util.MenuSlots;
import gg.modl.minecraft.core.impl.menus.util.ReportDismissal;
import gg.modl.minecraft.core.impl.menus.util.ReportRenderUtil;
import gg.modl.minecraft.core.impl.menus.util.MenuAsync;
import gg.modl.minecraft.core.impl.menus.util.StaffViewer;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.staff.action.StaffRequirements;
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

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public class StaffReportsMenu extends BaseStaffListMenu<StaffReportsMenu.Report> {
    @Getter
    public static class Report {
        private final String id, type, reporterName, reportedPlayerName, content, status;
        private final @Nullable UUID reportedPlayerUuid;
        private final @Nullable Instant date;

        public Report(String id, String type, String reporterName, String reportedPlayerName,
                      String content, String status, @Nullable UUID reportedPlayerUuid, @Nullable Instant date) {
            this.id = id;
            this.type = type;
            this.reporterName = reporterName;
            this.reportedPlayerName = reportedPlayerName;
            this.content = content;
            this.status = status;
            this.reportedPlayerUuid = reportedPlayerUuid;
            this.date = date;
        }

    }

    private final List<Report> reports = new ArrayList<>();
    private String currentFilter = ReportRenderUtil.ALL_REPORT_TYPES, currentStatusFilter = "open";
    @Getter private CompletableFuture<Void> dataFuture;

    public StaffReportsMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                            boolean isAdmin, PanelUrl panelUrl, @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        super(PluginServices.locale().getMessage("menus.titles.staff_reports"), platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction);

        this.dataFuture = fetchReports();
    }

    private CompletableFuture<Void> fetchReports() {
        return httpClient.getReports("all").thenAccept(response -> {
            if (response.isSuccess() && response.getReports() != null) {
                reports.clear();
                for (ReportsResponse.Report report : response.getReports()) {
                    reports.add(new Report(
                            report.getId(),
                            ReportRenderUtil.normalizeReportType(report),
                            report.getReporterName(),
                            report.getReportedPlayerName(),
                            ReportRenderUtil.getReportContent(report),
                            report.getStatus(),
                            ReportRenderUtil.parseReportedPlayerUuid(report),
                            report.getCreatedAt()
                    ));
                }
            }
        });
    }

    public StaffReportsMenu withFilter(String filter) {
        this.currentFilter = filter;
        return this;
    }

    public StaffReportsMenu withStatusFilter(String statusFilter) {
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
        return createEmptyPlaceholder(PluginServices.locale().getMessage("menus.empty.staff_reports"));
    }

    @Override
    protected CirrusItem mapItem(Report report) {
        LocaleManager locale = PluginServices.locale();

        Map<String, String> vars = new HashMap<>();
        vars.put("id", report.getId());
        vars.put("type", report.getType() != null ? report.getType() : locale.getUnknownWord());
        vars.put("reporter", report.getReporterName() != null ? report.getReporterName() : locale.getUnknownWord());
        vars.put("reported", report.getReportedPlayerName() != null ? report.getReportedPlayerName() : locale.getUnknownWord());
        vars.put("date", MenuItems.formatDate(report.getDate()));
        vars.put("status", ReportRenderUtil.statusLabel(locale, report.getStatus()));
        vars.put("content", String.join("\n", ReportRenderUtil.processContent(report.getContent())));

        List<String> lore = ReportRenderUtil.buildLore(locale, "menus.staff_report_item.lore", vars);
        String title = locale.getMessage("menus.staff_report_item.title", vars);
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
        else
            inspectPlayer(click, report);
    }

    private void dismissReport(Click click, Report report) {
        ReportDismissal.dismiss(platform, httpClient, viewerUuid, viewerName, report.getId(), () -> {
            StaffReportsMenu refreshed = new StaffReportsMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction)
                    .withFilter(currentFilter).withStatusFilter(currentStatusFilter);
            MenuAsync.displayWhenLoaded(platform, refreshed.getDataFuture(), click.player(), refreshed::display);
        });
    }

    private void inspectPlayer(Click click, Report report) {
        if (!new StaffViewer(platform, viewerUuid).authorize(StaffRequirements.VIEW_PLAYERS)) return;
        if (report.getReportedPlayerUuid() == null) {
            sendMessage(localeManager().getMessage("menus.common.no_reported_player"));
            return;
        }

        click.clickedMenu().close();

        httpClient.getPlayerProfile(report.getReportedPlayerUuid()).thenAccept(response -> {
            if (response.getStatus() == 200) {
                new InspectMenu(platform, httpClient, viewerUuid, viewerName, response.getProfile(),
                    p -> {
                        StaffReportsMenu m = new StaffReportsMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, null);
                        MenuAsync.displayWhenLoaded(platform, m.getDataFuture(), p, m::display);
                    })
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

        registerActionHandler("filter", this::handleFilter);

        registerActionHandler("openReports", click -> {});
    }

    private void handleFilter(Click click) {
        if (click.clickType().equals(CirrusClickType.RIGHT_CLICK)) {
            String newStatus = "open".equalsIgnoreCase(currentStatusFilter) ? "closed" : "open";
            StaffReportsMenu menu = new StaffReportsMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction)
                    .withFilter(currentFilter)
                    .withStatusFilter(newStatus);
            MenuAsync.displayWhenLoaded(platform, menu.getDataFuture(), click.player(), menu::display);
        } else {
            String newFilter = ReportRenderUtil.REPORT_TYPE_FILTERS.next(currentFilter);

            StaffReportsMenu menu = new StaffReportsMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction)
                    .withFilter(newFilter)
                    .withStatusFilter(currentStatusFilter);
            MenuAsync.displayWhenLoaded(platform, menu.getDataFuture(), click.player(), menu::display);
        }
    }
}
