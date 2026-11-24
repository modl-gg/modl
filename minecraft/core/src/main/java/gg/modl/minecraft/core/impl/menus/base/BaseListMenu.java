package gg.modl.minecraft.core.impl.menus.base;

import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import dev.simplix.cirrus.menu.CirrusInventoryType;
import dev.simplix.cirrus.menus.AbstractBrowser;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.impl.menus.util.MenuItems;
import gg.modl.minecraft.core.impl.menus.util.MenuSlots;
import gg.modl.minecraft.core.locale.LocaleManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import dev.simplix.cirrus.model.CallResult;
import org.jspecify.annotations.Nullable;

public abstract class BaseListMenu<T> extends AbstractBrowser<BrowserRow<T>> {

    protected final Platform platform;
    protected final ModlHttpClient httpClient;
    protected final UUID viewerUuid;
    protected final String viewerName;
    protected final @Nullable Consumer<CirrusPlayerWrapper> backAction;

    public BaseListMenu(String title, Platform platform, ModlHttpClient httpClient,
                        UUID viewerUuid, String viewerName, @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        super();
        this.platform = platform;
        this.httpClient = httpClient;
        this.viewerUuid = viewerUuid;
        this.viewerName = viewerName;
        this.backAction = backAction;

        title(title);
        fixedSize(CirrusInventoryType.GENERIC_9X6);
    }

    @Override
    protected Map<Integer, CirrusItem> intercept(int menuSize) {
        Map<Integer, CirrusItem> items = new HashMap<>();

        for (int i = 0; i <= 26; i++) items.put(i, null);

        items.put(27, null);
        items.put(35, null);

        for (int i = 36; i <= 53; i++) items.put(i, null);

        if (backAction != null) items.put(MenuSlots.BACK_BUTTON, MenuItems.backButton());

        return items;
    }

    @Override
    protected Map<Integer, CirrusItem> pageControls(int pageIndex, int pageTotal) {
        Map<Integer, CirrusItem> controls = new HashMap<>();
        if (pageIndex > 0) controls.put(MenuSlots.PAGE_PREV, MenuItems.previousPageButton());
        if (pageIndex < pageTotal - 1) controls.put(MenuSlots.PAGE_NEXT, MenuItems.nextPageButton());
        return controls;
    }

    @Override
    protected final Collection<BrowserRow<T>> elements() {
        Collection<T> items = items();
        if (items.isEmpty()) return Collections.singletonList(BrowserRow.placeholder());
        List<BrowserRow<T>> rows = new ArrayList<>(items.size());
        for (T item : items) rows.add(BrowserRow.of(item));
        return Collections.unmodifiableList(rows);
    }

    @Override
    protected final CirrusItem map(BrowserRow<T> row) {
        T item = row.item();
        return item == null ? emptyPlaceholder() : mapItem(item);
    }

    @Override
    protected final void handleClick(Click click, BrowserRow<T> row) {
        T item = row.item();
        if (item != null) handleItemClick(click, item);
    }

    protected abstract Collection<T> items();

    protected abstract CirrusItem emptyPlaceholder();

    protected abstract CirrusItem mapItem(T item);

    protected abstract void handleItemClick(Click click, T item);

    protected CirrusItem createEmptyPlaceholder(String message) {
        return CirrusItem.of(
                CirrusItemType.GRAY_STAINED_GLASS_PANE,
                CirrusChatElement.ofLegacyText(MenuItems.COLOR_GRAY + message)
        );
    }

    protected LocaleManager localeManager() {
        return PluginServices.locale();
    }

    @Override
    protected void registerActionHandlers() {
        registerActionHandler("back", click -> {
            handleBack(click);
            return CallResult.DENY_GRABBING;
        });
    }

    protected void handleBack(Click click) {
        MenuNavigation.handleBack(click, backAction);
    }

    protected void sendMessage(String message) {
        platform.sendMessage(viewerUuid, message);
    }

}
