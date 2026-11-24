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
import gg.modl.minecraft.api.http.response.OnlinePlayersResponse;
import gg.modl.minecraft.api.http.response.ReportsResponse;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.player.OnlinePlayerPages;
import gg.modl.minecraft.core.impl.menus.base.BaseStaffListMenu;
import gg.modl.minecraft.core.impl.menus.inspect.InspectMenu;
import gg.modl.minecraft.core.impl.menus.util.MenuItems;
import gg.modl.minecraft.core.impl.menus.util.OptionCycle;
import gg.modl.minecraft.core.impl.menus.util.MenuSlots;
import gg.modl.minecraft.core.impl.menus.util.ReportRenderUtil;
import gg.modl.minecraft.core.impl.menus.util.SkinTextureCache;
import gg.modl.minecraft.core.impl.menus.util.MenuAsync;
import gg.modl.minecraft.core.impl.menus.util.StaffViewer;
import gg.modl.minecraft.core.impl.menus.util.TargetPlayerAction;
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
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;
import java.util.function.Consumer;

public class OnlinePlayersMenu extends BaseStaffListMenu<OnlinePlayersMenu.OnlinePlayer> {
    private static final String SORT_LEAST_PLAYTIME = "least_playtime", SORT_RECENT_GAMEPLAY_REPORTS = "recent_gameplay_reports",
            SORT_LONGEST_SESSION = "longest_session";
    private static final OptionCycle SORTS = OptionCycle.of("menus.online_players.sorts.",
            SORT_LEAST_PLAYTIME, SORT_RECENT_GAMEPLAY_REPORTS, SORT_LONGEST_SESSION);

    @Getter
    public static class ReportSummary {
        private final String details, reporter;
        private final @Nullable Instant date;

        public ReportSummary(String details, String reporter, @Nullable Instant date) {
            this.details = details;
            this.reporter = reporter;
            this.date = date;
        }
    }

    public static class OnlinePlayer {
        @Getter private final UUID uuid;
        @Getter private final String name;
        private final long sessionStartTime;
        private final long earlierPlaytime;
        @Getter private List<ReportSummary> recentReports = new ArrayList<>();

        private OnlinePlayer(UUID uuid, String name, long sessionStartTime, long earlierPlaytime) {
            this.uuid = uuid;
            this.name = name;
            this.sessionStartTime = sessionStartTime;
            this.earlierPlaytime = earlierPlaytime;
        }

        public static OnlinePlayer fromSnapshot(UUID uuid, String name, long sessionStartTime, long totalPlaytimeAtSnapshot,
                                                long snapshotTime) {
            long sessionAtSnapshot = Math.max(0L, snapshotTime - sessionStartTime);
            long earlierPlaytime = Math.max(0L, totalPlaytimeAtSnapshot - sessionAtSnapshot);
            return new OnlinePlayer(uuid, name, sessionStartTime, earlierPlaytime);
        }

        public long sessionDurationAt(long now) { return Math.max(0L, now - sessionStartTime); }
        public long totalPlaytimeAt(long now) { return earlierPlaytime + sessionDurationAt(now); }
        public int getRecentReportCount() { return recentReports.size(); }
    }

    private List<OnlinePlayer> onlinePlayers = new ArrayList<>();
    private String currentSort;
    @Getter private CompletableFuture<Void> dataFuture;

    public OnlinePlayersMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                             boolean isAdmin, PanelUrl panelUrl, @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        this(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction, SORT_RECENT_GAMEPLAY_REPORTS, null);
    }

    public OnlinePlayersMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                             boolean isAdmin, PanelUrl panelUrl, @Nullable Consumer<CirrusPlayerWrapper> backAction,
                             String sortOption, @Nullable List<OnlinePlayer> existingPlayers) {
        super(PluginServices.locale().getMessage("menus.titles.online_players"), platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction);
        this.currentSort = sortOption;

        if (existingPlayers != null) {
            this.onlinePlayers = new ArrayList<>(existingPlayers);
            this.dataFuture = CompletableFuture.completedFuture(null);
        } else {
            this.dataFuture = fetchOnlinePlayers().thenCompose(v -> fetchReportData().exceptionally(reportsUnavailable -> null));
        }
    }

    private CompletableFuture<Void> fetchOnlinePlayers() {
        return OnlinePlayerPages.all(httpClient).thenAccept(players -> {
            long snapshotTime = System.currentTimeMillis();
            onlinePlayers.clear();
            for (OnlinePlayersResponse.OnlinePlayer player : players) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(player.getUuid());
                } catch (IllegalArgumentException malformed) {
                    continue;
                }

                long sessionStart = player.getJoinedAt() != null ? player.getJoinedAt().toEpochMilli() : System.currentTimeMillis();
                long totalPlaytime = player.getTotalPlaytimeMs() != null ? player.getTotalPlaytimeMs() : 0;
                onlinePlayers.add(OnlinePlayer.fromSnapshot(uuid, player.getUsername(), sessionStart, totalPlaytime, snapshotTime));
            }
        });
    }

    private CompletableFuture<Void> fetchReportData() {
        return httpClient.getReports("open").thenAccept(response -> {
            if (!response.isSuccess() || response.getReports() == null) return;

            Map<String, List<ReportSummary>> reportsByPlayer = new HashMap<>();
            for (ReportsResponse.Report report : response.getReports()) {
                String type = report.getType() != null ? report.getType() : report.getCategory();
                if ("player".equalsIgnoreCase(type)) type = "gameplay";
                if (!"gameplay".equalsIgnoreCase(type)) continue;

                String uuid = report.getReportedPlayerUuid();
                if (uuid == null) continue;

                String details = ReportRenderUtil.extractReportDetails(report.getContent(), report.getSubject());
                String reporter = report.getReporterName() != null ? report.getReporterName() : localeManager().getUnknownWord();
                reportsByPlayer.computeIfAbsent(uuid, k -> new ArrayList<>())
                        .add(new ReportSummary(details, reporter, report.getCreatedAt()));
            }

            for (OnlinePlayer player : onlinePlayers) {
                List<ReportSummary> reports = reportsByPlayer.get(player.getUuid().toString());
                if (reports == null) continue;

                reports.sort(Comparator.comparing(ReportSummary::getDate, Comparator.nullsLast(Comparator.reverseOrder())));
                player.recentReports = reports;
            }
        });
    }

    @Override
    protected Map<Integer, CirrusItem> intercept(int menuSize) {
        Map<Integer, CirrusItem> items = super.intercept(menuSize);

        items.put(MenuSlots.SORT_BUTTON, MenuItems.sortButton(SORTS, currentSort));

        return items;
    }

    @Override
    protected Collection<OnlinePlayer> items() {
        List<OnlinePlayer> sorted = new ArrayList<>(onlinePlayers);
        long now = System.currentTimeMillis();

        switch (currentSort) {
            case SORT_LEAST_PLAYTIME:
                sorted.sort(Comparator.comparingLong(player -> player.totalPlaytimeAt(now)));
                break;
            case SORT_LONGEST_SESSION:
                sorted.sort((p1, p2) -> Long.compare(p2.sessionDurationAt(now), p1.sessionDurationAt(now)));
                break;
            case SORT_RECENT_GAMEPLAY_REPORTS:
            default:
                sorted.sort((p1, p2) -> Integer.compare(p2.getRecentReportCount(), p1.getRecentReportCount()));
                break;
        }

        return sorted;
    }

    @Override
    protected CirrusItem emptyPlaceholder() {
        return createEmptyPlaceholder(localeManager().getMessage("menus.empty.online_players"));
    }

    @Override
    protected CirrusItem mapItem(OnlinePlayer player) {
        LocaleManager localeManager = PluginServices.locale();

        long now = System.currentTimeMillis();
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player_name", player.getName());
        placeholders.put("session_duration", MenuItems.formatDuration(player.sessionDurationAt(now)));
        placeholders.put("total_playtime", MenuItems.formatDuration(player.totalPlaytimeAt(now)));
        placeholders.put("report_count", String.valueOf(player.getRecentReportCount()));

        String title = localeManager.getMessage("menus.online_players.title", placeholders);
        List<String> templateLore = localeManager.getMessageList("menus.online_players.lore", placeholders);

        List<String> lore = new ArrayList<>();
        for (int index = 0; index < templateLore.size(); index++) {
            String line = templateLore.get(index);
            if (line.contains("{report-list}")) {
                List<ReportSummary> reports = player.getRecentReports();
                if (reports.isEmpty()) {
                    if (!lore.isEmpty()) lore.remove(lore.size() - 1);
                    boolean followedByBlank = index + 1 < templateLore.size() && templateLore.get(index + 1).trim().isEmpty();
                    if (followedByBlank && !lore.isEmpty() && lore.get(lore.size() - 1).trim().isEmpty()) index++;
                } else {
                    int shown = Math.min(reports.size(), 5);
                    for (int i = 0; i < shown; i++) {
                        ReportSummary report = reports.get(i);
                        Map<String, String> reportVars = new HashMap<>();
                        reportVars.put("date", MenuItems.formatDate(report.getDate()));
                        reportVars.put("details", report.getDetails());
                        reportVars.put("reporter", report.getReporter());
                        lore.add(localeManager.getMessage("menus.online_players.report_entry", reportVars));
                    }
                    if (reports.size() > 5) {
                        lore.add(localeManager.getMessage("menus.online_players.reports_more",
                                mapOf("count", String.valueOf(reports.size() - 5))));
                    }
                }
            } else {
                lore.add(line);
            }
        }

        CirrusItem headItem = CirrusItem.of(
                CirrusItemType.PLAYER_HEAD,
                CirrusChatElement.ofLegacyText(title),
                MenuItems.lore(lore)
        );

        headItem = SkinTextureCache.applyCachedOrFetch(headItem, player.getUuid());

        return headItem;
    }

    @Override
    protected void handleItemClick(Click click, OnlinePlayer player) {
        if (click.clickType().equals(CirrusClickType.RIGHT_CLICK)) {
            TargetPlayerAction.handle(click, platform, viewerUuid, player.getUuid());
            return;
        }
        if (!new StaffViewer(platform, viewerUuid).authorize(StaffRequirements.VIEW_PLAYERS)) return;

        click.clickedMenu().close();

        final String sortState = currentSort;
        final List<OnlinePlayer> playerState = new ArrayList<>(onlinePlayers);

        httpClient.getPlayerProfile(player.getUuid()).thenAccept(response -> {
            if (response.getStatus() == 200) {
                new InspectMenu(platform, httpClient, viewerUuid, viewerName, response.getProfile(),
                    p -> {
                        OnlinePlayersMenu m = new OnlinePlayersMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, null,
                                sortState, playerState);
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

        registerActionHandler("sort", this::handleSort);

        registerActionHandler("openOnlinePlayers", click -> {});
    }

    private void handleSort(Click click) {
        String nextSort = SORTS.next(currentSort);

        OnlinePlayersMenu menu = new OnlinePlayersMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction,
                nextSort, onlinePlayers);
        MenuAsync.displayWhenLoaded(platform, menu.getDataFuture(), click.player(), menu::display);
    }
}
