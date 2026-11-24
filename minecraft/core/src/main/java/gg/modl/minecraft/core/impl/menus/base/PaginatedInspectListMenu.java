package gg.modl.minecraft.core.impl.menus.base;

import dev.simplix.cirrus.menu.DisplayedMenu;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.pagination.PaginatedDataSource;
import gg.modl.minecraft.core.impl.menus.pagination.PaginatedDataSource.FetchResult;
import gg.modl.minecraft.core.impl.menus.util.InspectContext;

import gg.modl.minecraft.core.impl.menus.util.StaffViewer;
import gg.modl.minecraft.core.staff.action.StaffRequirements;
import org.jspecify.annotations.Nullable;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public abstract class PaginatedInspectListMenu<T> extends BaseInspectListMenu<T> {

    private final int pageSize;
    protected final PaginatedDataSource<T> dataSource;
    private int pageRefreshRequest;

    protected PaginatedInspectListMenu(String title, Platform platform, ModlHttpClient httpClient,
                                       UUID viewerUuid, String viewerName, Account targetAccount,
                                       @Nullable Consumer<CirrusPlayerWrapper> backAction, @Nullable InspectContext inspectContext, int pageSize) {
        super(title, platform, httpClient, viewerUuid, viewerName, targetAccount, backAction, inspectContext);
        this.pageSize = pageSize;
        this.dataSource = new PaginatedDataSource<>(pageSize, this::fetchPage);
    }

    protected abstract CompletableFuture<FetchResult<T>> fetchPage(int page, int limit);

    @Override
    public @Nullable DisplayedMenu display(CirrusPlayerWrapper player) {
        return new StaffViewer(platform, viewerUuid).authorize(StaffRequirements.VIEW_PLAYERS) ? super.display(player) : null;
    }

    @Override
    protected boolean interceptNextPage(Click click) {
        int nextPage = currentPageIndex().get() + 1;
        if (!dataSource.isPageLoaded(nextPage)) {
            int refreshRequest = ++pageRefreshRequest;
            dataSource.fetchPage(dataSource.getAllLoadedItems().size() / pageSize + 1, () -> {
                if (refreshRequest != pageRefreshRequest) return;
                openLoadedPage(click, nextPage);
            });
            return true;
        }
        dataSource.prefetchIfNeeded(nextPage);
        return false;
    }

    @Override
    protected int pageTotal(int builtPages) {
        return Math.max(builtPages, dataSource.getTotalMenuPages());
    }

    @Override
    protected Collection<T> items() {
        return dataSource.getAllLoadedItems();
    }

    protected abstract void openLoadedPage(Click click, int nextPage);
}
