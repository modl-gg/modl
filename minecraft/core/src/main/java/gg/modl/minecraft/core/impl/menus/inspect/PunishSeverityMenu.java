package gg.modl.minecraft.core.impl.menus.inspect;

import gg.modl.minecraft.api.Punishment;
import gg.modl.minecraft.core.PluginServices;
import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import dev.simplix.cirrus.model.CallResult;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.request.PunishmentCreateRequest;
import gg.modl.minecraft.api.http.response.PunishmentPreviewResponse;
import gg.modl.minecraft.api.http.response.PunishmentTypesResponse;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.LinkReportsMenu;
import gg.modl.minecraft.core.impl.menus.base.BaseInspectMenu;
import gg.modl.minecraft.core.impl.menus.base.InspectChrome;
import gg.modl.minecraft.core.impl.menus.util.MenuAsync;
import gg.modl.minecraft.core.impl.menus.util.MenuItems;
import gg.modl.minecraft.core.impl.menus.util.MenuSlots;
import gg.modl.minecraft.core.impl.menus.util.ReportResolution;
import gg.modl.minecraft.core.impl.menus.util.StaffViewer;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.staff.action.StaffRequirements;
import gg.modl.minecraft.core.util.ApiFailures;
import lombok.Getter;
import lombok.Value;
import lombok.With;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static gg.modl.minecraft.core.util.Java8Collections.listOf;
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public class PunishSeverityMenu extends BaseInspectMenu {
    private static final int SEVERITY_LEVEL_LENIENT = 0, SEVERITY_LEVEL_REGULAR = 1, SEVERITY_LEVEL_AGGRAVATED = 2;
    private static final String SEVERITY_KEY = "menus.punish_severity.";

    private final PunishmentTypesResponse.PunishmentTypeData punishmentType;
    private final @Nullable Consumer<CirrusPlayerWrapper> menuBackAction, rootBackAction;
    private final PunishmentOptions options;
    private @Nullable PunishmentPreviewResponse previewData;
    @Getter private final CompletableFuture<Void> dataFuture;

    @Value
    @With
    public static class PunishmentOptions {
        boolean silent;
        boolean altBlocking;
        boolean statWipe;
        List<String> linkedReportIds;

        static PunishmentOptions defaults() {
            return new PunishmentOptions(false, false, false, new ArrayList<>());
        }
    }

    public PunishSeverityMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                               Account targetAccount, PunishmentTypesResponse.PunishmentTypeData punishmentType,
                               @Nullable Consumer<CirrusPlayerWrapper> rootBackAction, @Nullable Consumer<CirrusPlayerWrapper> menuBackAction) {
        this(platform, httpClient, viewerUuid, viewerName, targetAccount, punishmentType, rootBackAction, menuBackAction, PunishmentOptions.defaults());
    }

    public PunishSeverityMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                               Account targetAccount, PunishmentTypesResponse.PunishmentTypeData punishmentType,
                               @Nullable Consumer<CirrusPlayerWrapper> rootBackAction, @Nullable Consumer<CirrusPlayerWrapper> menuBackAction,
                               PunishmentOptions options) {
        super(platform, httpClient, viewerUuid, viewerName, targetAccount, menuBackAction, rootBackAction);
        this.punishmentType = punishmentType;
        this.menuBackAction = menuBackAction;
        this.rootBackAction = rootBackAction;
        this.options = options;

        title(localeManager().getMessage("menus.titles.punish_type", mapOf("type", String.valueOf(punishmentType.getName()))));

        this.dataFuture = loadPreviewData().thenRun(this::buildMenu);
    }

    private CompletableFuture<Void> loadPreviewData() {
        return httpClient.getPunishmentPreview(targetUuid, punishmentType.getOrdinal()).thenAccept(response -> {
            if (response.isSuccess()) {
                this.previewData = response;
            }
        }).exceptionally(e -> null);
    }

    private void buildMenu() {
        buildHeader();

        Boolean singleSeverity = punishmentType.getSingleSeverityPunishment();
        Boolean permUsername = punishmentType.getPermanentUntilUsernameChange();
        Boolean permSkin = punishmentType.getPermanentUntilSkinChange();

        boolean isSingleType = (singleSeverity != null && singleSeverity) ||
                               (permUsername != null && permUsername) ||
                               (permSkin != null && permSkin);

        if (isSingleType)
            buildSingleSeverityLayout();
        else
            buildMultiSeverityLayout();

        buildToggleButtons();
    }

    private void buildSingleSeverityLayout() {
        LocaleManager locale = localeManager();
        PunishmentPreviewResponse.SeverityPreview preview = previewData != null ?
                previewData.getSingleSeverity() : null;

        boolean restrictedUntilUsername = Boolean.TRUE.equals(punishmentType.getPermanentUntilUsernameChange());
        boolean restrictedUntilSkin = Boolean.TRUE.equals(punishmentType.getPermanentUntilSkinChange());

        List<String> lore = new ArrayList<>();

        if (!restrictedUntilUsername && !restrictedUntilSkin && preview != null) {
            lore.addAll(previewLines(locale, preview));
        }

        if (restrictedUntilUsername) {
            lore.addAll(locale.getMessageList(SEVERITY_KEY + "restricted_until_username"));
            lore.add("");
        } else if (restrictedUntilSkin) {
            lore.addAll(locale.getMessageList(SEVERITY_KEY + "restricted_until_skin"));
            lore.add("");
        }

        lore.add(locale.getMessage(SEVERITY_KEY + (options.isSilent() ? "single_click_to_issue_silent" : "single_click_to_issue")));

        set(CirrusItem.of(
                CirrusItemType.YELLOW_WOOL,
                CirrusChatElement.ofLegacyText(locale.getMessage(SEVERITY_KEY + "issue_punishment")),
                MenuItems.lore(lore)
        ).slot(31).actionHandler("issueSingle"));
    }

    private void buildMultiSeverityLayout() {
        set(createSeverityButton("lenient", CirrusItemType.LIME_WOOL,
                previewData != null ? previewData.getLenient() : null, "issueLenient", MenuSlots.SEVERITY_LENIENT));

        set(createSeverityButton("regular", CirrusItemType.YELLOW_WOOL,
                previewData != null ? previewData.getRegular() : null, "issueRegular", MenuSlots.SEVERITY_REGULAR));

        set(createSeverityButton("aggravated", CirrusItemType.RED_WOOL,
                previewData != null ? previewData.getAggravated() : null, "issueAggravated", MenuSlots.SEVERITY_AGGRAVATED));
    }

    private CirrusItem createSeverityButton(String severity, CirrusItemType itemType,
                                             PunishmentPreviewResponse.@Nullable SeverityPreview preview, String action, int slot) {
        LocaleManager locale = localeManager();
        String severityKey = SEVERITY_KEY + "severities." + severity;
        List<String> lore = new ArrayList<>();

        if (preview != null) {
            lore.addAll(previewLines(locale, preview));
        }

        lore.add(locale.getMessage(SEVERITY_KEY + (options.isSilent() ? "click_to_issue_silent" : "click_to_issue")));

        return CirrusItem.of(
                itemType,
                CirrusChatElement.ofLegacyText(locale.getMessage(severityKey + ".title")),
                MenuItems.lore(lore)
        ).slot(slot).actionHandler(action);
    }

    private List<String> previewLines(LocaleManager locale, PunishmentPreviewResponse.SeverityPreview preview) {
        List<String> lines = new ArrayList<>();
        lines.add(locale.getMessage(SEVERITY_KEY + (preview.isPermanent() ? "preview_duration_permanent" : "preview_duration"),
                mapOf("duration", String.valueOf(preview.getDurationFormatted()))));
        lines.add(locale.getMessage(SEVERITY_KEY + "preview_type", mapOf("type", String.valueOf(capitalizeFirst(preview.getPunishmentType())))));
        lines.add(locale.getMessage(SEVERITY_KEY + "preview_points", mapOf("points", String.valueOf(preview.getPoints()))));
        lines.add("");

        PunishmentPreviewResponse response = previewData;
        if (response != null) {
            lines.add(offenderLevelLine(locale, response, preview));
            lines.add("");
        }
        return lines;
    }

    private String offenderLevelLine(LocaleManager locale, PunishmentPreviewResponse response,
                                     PunishmentPreviewResponse.SeverityPreview preview) {
        boolean social = "Social".equalsIgnoreCase(punishmentType.getCategory());
        String current = social ? response.getSocialStatus() : response.getGameplayStatus();
        String next = social ? preview.getNewSocialStatus() : preview.getNewGameplayStatus();
        return locale.getMessage(SEVERITY_KEY + "offender_level", mapOf(
                "current", getOffenseLevelColor(current) + current,
                "next", getOffenseLevelColor(next) + next));
    }

    private void buildToggleButtons() {
        LocaleManager locale = localeManager();
        List<String> linkedReportIds = options.getLinkedReportIds();
        String linkTitle = linkedReportIds.isEmpty()
                ? locale.getMessage(SEVERITY_KEY + "link_reports")
                : locale.getMessage(SEVERITY_KEY + "link_reports_selected", mapOf("count", String.valueOf(linkedReportIds.size())));
        List<String> linkLore;
        if (linkedReportIds.isEmpty()) {
            linkLore = listOf(locale.getMessage(SEVERITY_KEY + "select_reports"));
        } else {
            List<String> linked = new ArrayList<>();
            linked.add(locale.getMessage(SEVERITY_KEY + "linked_reports_header"));
            for (String reportId : linkedReportIds) {
                linked.add(locale.getMessage(SEVERITY_KEY + "linked_report_entry", mapOf("id", reportId)));
            }
            linkLore = MenuItems.loreSections(linked, listOf(locale.getMessage(SEVERITY_KEY + "modify_selection")));
        }
        set(CirrusItem.of(
                CirrusItemType.BOOK,
                CirrusChatElement.ofLegacyText(linkTitle),
                MenuItems.lore(linkLore)
        ).slot(MenuSlots.SEVERITY_LINK_REPORTS).actionHandler("linkReports"));

        set(toggleItem(locale, "silent_mode", options.isSilent(),
                options.isSilent() ? CirrusItemType.LIME_DYE : CirrusItemType.GRAY_DYE, "silent_on", "silent_off")
                .slot(MenuSlots.SEVERITY_SILENT).actionHandler("toggleSilent"));

        if (Boolean.TRUE.equals(punishmentType.getCanBeAltBlocking())) {
            set(toggleItem(locale, "alt_blocking", options.isAltBlocking(),
                    options.isAltBlocking() ? CirrusItemType.TORCH : CirrusItemType.REDSTONE_TORCH,
                    "alt_blocking_on", "alt_blocking_off")
                    .slot(MenuSlots.SEVERITY_ALT_BLOCK).actionHandler("toggleAltBlock"));
        }

        if (Boolean.TRUE.equals(punishmentType.getCanBeStatWiping())) {
            set(toggleItem(locale, "stat_wipe", options.isStatWipe(),
                    options.isStatWipe() ? CirrusItemType.EXPERIENCE_BOTTLE : CirrusItemType.GLASS_BOTTLE,
                    "stat_wipe_on", "stat_wipe_off")
                    .slot(MenuSlots.SEVERITY_STAT_WIPE).actionHandler("toggleStatWipe"));
        }
    }

    private CirrusItem toggleItem(LocaleManager locale, String toggleKey, boolean enabled, CirrusItemType itemType,
                                  String enabledKey, String disabledKey) {
        String state = locale.getMessage(enabled ? "menus.common.enabled" : "menus.common.disabled");
        List<String> lore = MenuItems.loreSections(
                listOf(locale.getMessage(SEVERITY_KEY + (enabled ? enabledKey : disabledKey))),
                listOf(locale.getMessage(MenuItems.CLICK_TO_TOGGLE)));
        return CirrusItem.of(
                itemType,
                CirrusChatElement.ofLegacyText(locale.getMessage(SEVERITY_KEY + toggleKey, mapOf("state", state))),
                MenuItems.lore(lore)
        );
    }

    private String getOffenseLevelColor(String offenseLevel) {
        if (offenseLevel == null) return MenuItems.COLOR_GRAY;
        String lower = offenseLevel.toLowerCase(Locale.ROOT);
        if ("low".equals(lower)) return MenuItems.COLOR_GREEN;
        if ("medium".equals(lower)) return MenuItems.COLOR_YELLOW;
        if ("habitual".equals(lower)) return MenuItems.COLOR_RED;
        return MenuItems.COLOR_GRAY;
    }

    private String capitalizeFirst(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase(Locale.ROOT) + str.substring(1).toLowerCase(Locale.ROOT);
    }

    @Override
    protected void registerActionHandlers() {
        super.registerActionHandlers();

        registerActionHandler("issueSingle", click -> {
            issuePunishment(click, SEVERITY_LEVEL_REGULAR);
            return CallResult.DENY_GRABBING;
        });
        registerActionHandler("issueLenient", click -> {
            issuePunishment(click, SEVERITY_LEVEL_LENIENT);
            return CallResult.DENY_GRABBING;
        });
        registerActionHandler("issueRegular", click -> {
            issuePunishment(click, SEVERITY_LEVEL_REGULAR);
            return CallResult.DENY_GRABBING;
        });
        registerActionHandler("issueAggravated", click -> {
            issuePunishment(click, SEVERITY_LEVEL_AGGRAVATED);
            return CallResult.DENY_GRABBING;
        });

        registerActionHandler("toggleSilent", this::handleToggleSilent);
        registerActionHandler("toggleAltBlock", this::handleToggleAltBlock);
        registerActionHandler("toggleStatWipe", this::handleToggleStatWipe);

        registerActionHandler("linkReports", click -> {
            handleLinkReports(click);
            return CallResult.DENY_GRABBING;
        });
    }

    private void issuePunishment(Click click, int severityLevel) {
        boolean silentMode = options.isSilent();
        boolean altBlocking = options.isAltBlocking();
        boolean statWipe = options.isStatWipe();
        List<String> linkedReportIds = options.getLinkedReportIds();

        new StaffViewer(platform, viewerUuid).promptOptionalFromMenu(click,
                StaffRequirements.applyPunishment(punishmentType.getOrdinal()),
                localeManager().getMessage(SEVERITY_KEY + "reason_prompt", mapOf("player", targetName)),
                (issuer, reason) -> {
                    String severityStr = severityLevel == SEVERITY_LEVEL_LENIENT ? "lenient"
                            : severityLevel == SEVERITY_LEVEL_REGULAR ? "regular" : "aggravated";

                    Map<String, Object> data = new HashMap<>();
                    data.put("altBlocking", altBlocking);
                    data.put(Punishment.DATA_KEY_WIPE_AFTER_EXPIRY, statWipe);
                    data.put("silent", silentMode);
                    data.put("issuedServer", platform.getPlayerServer(viewerUuid));

                    PunishmentCreateRequest request = new PunishmentCreateRequest(
                            targetUuid.toString(),
                            viewerName,
                            issuer.actionIssuer(),
                            reason,
                            severityStr,
                            null,
                            punishmentType.getOrdinal(),
                            null,
                            data,
                            Collections.emptyList(),
                            linkedReportIds
                    );

                    httpClient.createPunishmentWithResponse(request).thenAccept(response -> {
                        if (response.isSuccess()) {
                            LocaleManager localeManager = PluginServices.locale();

                            String successMessage = localeManager.punishment()
                                .type(punishmentType.getName())
                                .target(targetName)
                                .punishmentId(response.getPunishmentId())
                                .get("general.punishment_issued");
                            sendMessage(successMessage);

                            if (response.getPunishmentId() != null) {
                                platform.runOnMainThread(() -> PluginServices.punishmentActions().sendPunishmentActions(viewerUuid, response.getPunishmentId()));
                            }

                            if (!linkedReportIds.isEmpty()) {
                                MenuAsync.afterSave(platform, viewerUuid, ReportResolution.resolveLinked(httpClient,
                                        linkedReportIds, viewerName, response.getPunishmentId(), issuer.actionIssuer(),
                                        this::sendMessage));
                            }
                        } else {
                            sendMessage(PluginServices.locale().getMessage(SEVERITY_KEY + "issue_failed",
                                    mapOf("error", String.valueOf(response.getMessage()))));
                        }
                    }).exceptionally(e -> {
                        sendMessage(ApiFailures.describe(localeManager(), SEVERITY_KEY + "issue_failed", e));
                        return null;
                    });
                },
                () -> {
                    sendMessage(localeManager().getMessage(SEVERITY_KEY + "cancelled"));
                    display(click.player());
                }
        );
    }

    private void handleToggleSilent(Click click) {
        rebuildWith(click.player(), options.withSilent(!options.isSilent()));
    }

    private void handleToggleAltBlock(Click click) {
        rebuildWith(click.player(), options.withAltBlocking(!options.isAltBlocking()));
    }

    private void handleToggleStatWipe(Click click) {
        rebuildWith(click.player(), options.withStatWipe(!options.isStatWipe()));
    }

    private void rebuildWith(CirrusPlayerWrapper player, PunishmentOptions options) {
        PunishSeverityMenu m = new PunishSeverityMenu(platform, httpClient, viewerUuid, viewerName, targetAccount, punishmentType,
                rootBackAction, menuBackAction, options);
        MenuAsync.displayWhenLoaded(platform, m.getDataFuture(), player, m::display);
    }

    private void handleLinkReports(Click click) {
        Set<String> preSelected = new HashSet<>(options.getLinkedReportIds());
        Consumer<Set<String>> onComplete = selectedIds ->
                rebuildWith(click.player(), options.withLinkedReportIds(new ArrayList<>(selectedIds)));

        Consumer<CirrusPlayerWrapper> backToSeverity = player ->
                rebuildWith(player, options);

        LinkReportsMenu linkMenu = new LinkReportsMenu(platform, httpClient, viewerUuid, viewerName,
                new InspectChrome(platform, httpClient, viewerUuid, viewerName, targetAccount, rootBackAction, null),
                targetAccount, preSelected, backToSeverity, onComplete);
        MenuAsync.displayWhenLoaded(platform, linkMenu.getDataFuture(), click.player(), linkMenu::display);
    }
}
