package gg.modl.minecraft.core.impl.menus;

import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.response.PlayerStandingResponse;
import gg.modl.minecraft.api.http.response.PlayerStandingResponse.Entry;
import gg.modl.minecraft.api.http.response.PlayerStandingResponse.State;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.config.ConfigManager.StandingGuiConfig;
import gg.modl.minecraft.core.impl.menus.base.BaseListMenu;
import gg.modl.minecraft.core.impl.menus.util.MenuItems;
import gg.modl.minecraft.core.locale.LocaleManager;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import static gg.modl.minecraft.core.util.Java8Collections.listOf;
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public class StandingMenu extends BaseListMenu<Entry> {
    private static final String STATUS_KEY = "standing_gui.punishment_item.";
    private static final int SOCIAL_SLOT = 11, GAMEPLAY_SLOT = 15;

    private final PlayerStandingResponse standing;
    private final StandingGuiConfig guiConfig;
    private final LocaleManager localeManager;

    public StandingMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                        PlayerStandingResponse standing, StandingGuiConfig guiConfig, LocaleManager localeManager) {
        super(localeManager.getMessage("standing_gui.title"), platform, httpClient,
                viewerUuid, viewerName, null);
        this.standing = standing;
        this.guiConfig = guiConfig;
        this.localeManager = localeManager;
    }

    @Override
    protected Map<Integer, CirrusItem> intercept(int menuSize) {
        Map<Integer, CirrusItem> items = super.intercept(menuSize);

        items.put(SOCIAL_SLOT, buildStatusItem(
                guiConfig.getSocialItem(),
                "standing_gui.social_status",
                standing.getSocialStatus(), standing.getSocialPoints()
        ));

        items.put(GAMEPLAY_SLOT, buildStatusItem(
                guiConfig.getGameplayItem(),
                "standing_gui.gameplay_status",
                standing.getGameplayStatus(), standing.getGameplayPoints()
        ));

        return items;
    }

    @Override
    protected Collection<Entry> items() {
        List<Entry> punishments = standing.getPunishments();
        return punishments != null ? punishments : Collections.emptyList();
    }

    @Override
    protected CirrusItem emptyPlaceholder() {
        return createEmptyPlaceholder(localeManager.getMessage("menus.empty.punishments"));
    }

    @Override
    protected CirrusItem mapItem(Entry entry) {
        Long durationMs = entry.getDurationMs();
        String duration = durationMs != null && durationMs > 0
                ? MenuItems.formatDuration(durationMs)
                : localeManager.getMessage("general.permanent");
        String typeName = entry.getType() != null ? entry.getType() : localeManager.getUnknownWord();

        Map<String, String> vars = mapOf(
                "id", entry.getId(),
                "date", MenuItems.formatDate(Instant.ofEpochMilli(entry.getIssuedAt())),
                "duration", duration,
                "type", kindLabel(entry, typeName),
                "type_name", typeName,
                "status", buildStatusString(entry),
                "reason", entry.getReason() != null ? entry.getReason() : localeManager.getMessage("config.default_reason")
        );

        String title = localeManager.getMessage("standing_gui.punishment_item.title", vars);
        List<String> lore = localeManager.getMessageList("standing_gui.punishment_item.lore", vars);
        CirrusItemType itemType = entry.isBan() ? CirrusItemType.DIAMOND_SWORD : CirrusItemType.PAPER;

        return CirrusItem.of(itemType, CirrusChatElement.ofLegacyText(title),
                MenuItems.lore(lore));
    }

    @Override
    protected void handleItemClick(Click click, Entry entry) {
    }

    private String kindLabel(Entry entry, String typeName) {
        if (entry.isBan()) return localeManager.getMessage("punishment_words.type_ban");
        if (entry.isMute()) return localeManager.getMessage("punishment_words.type_mute");
        return typeName;
    }

    private CirrusItem buildStatusItem(String itemId, String localeBasePath, @Nullable String level, int points) {
        String status = level != null ? level : localeManager.getUnknownWord();
        String statusKey = "standing_gui.status_display." + status;
        String displayStatus = localeManager.hasMessage(statusKey) ? localeManager.getMessage(statusKey) : status;

        Map<String, String> placeholders = mapOf(
                "status", displayStatus,
                "points", String.valueOf(points)
        );

        String title = localeManager.getMessage(localeBasePath + ".title", placeholders);

        List<String> desc = localeManager.getMessageList(localeBasePath + ".description." + status, placeholders);
        if (desc.size() == 1 && desc.get(0).contains("Missing locale list")) {
            String capitalized = status.isEmpty() ? status : status.substring(0, 1).toUpperCase(Locale.ROOT) + status.substring(1).toLowerCase(Locale.ROOT);
            desc = localeManager.getMessageList(localeBasePath + ".description." + capitalized, placeholders);
            if (desc.size() == 1 && desc.get(0).contains("Missing locale list")) {
                desc = listOf();
            }
        }

        CirrusItemType type = CirrusItemType.of(itemId);
        return CirrusItem.of(type, CirrusChatElement.ofLegacyText(title),
                MenuItems.lore(desc));
    }

    private String buildStatusString(Entry entry) {
        long now = System.currentTimeMillis();
        State state = entry.getState();
        Long endedAt = entry.getEndedAt();
        if (endedAt != null && state == State.PARDONED) return statusWithDuration("status_pardoned", now - endedAt);
        if (endedAt != null && state == State.ROLLED_BACK) return statusWithDuration("status_rolled_back", now - endedAt);
        if (state == State.QUEUED) return localeManager.getMessage(STATUS_KEY + "status_queued");

        Long expiresAt = entry.getExpiresAt();
        if (state == State.ACTIVE) {
            if (expiresAt == null) return localeManager.getMessage(STATUS_KEY + "status_permanent");
            return statusWithDuration("status_expires", expiresAt - now);
        }
        if (expiresAt == null) return localeManager.getMessage(STATUS_KEY + "status_expired");
        return statusWithDuration("status_expired_ago", now - expiresAt);
    }

    private String statusWithDuration(String statusKey, long durationMs) {
        return localeManager.getMessage(STATUS_KEY + statusKey,
                mapOf("duration", MenuItems.formatDuration(durationMs > 0 ? durationMs : 0)));
    }
}
