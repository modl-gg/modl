package gg.modl.minecraft.core.impl.menus.base;

import dev.simplix.cirrus.menu.CirrusInventoryType;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.util.InspectContext;
import gg.modl.minecraft.core.impl.menus.util.ReportRenderUtil;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

public abstract class BaseInspectMenu extends ChromeMenu {

    protected final Account targetAccount;
    protected final String targetName;
    protected final UUID targetUuid;


    public BaseInspectMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                           Account targetAccount, @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        this(platform, httpClient, viewerUuid, viewerName, targetAccount, backAction, backAction,
                CirrusInventoryType.GENERIC_9X6, null);
    }

    public BaseInspectMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                           Account targetAccount, @Nullable Consumer<CirrusPlayerWrapper> backAction,
                           @Nullable Consumer<CirrusPlayerWrapper> navBackAction) {
        this(platform, httpClient, viewerUuid, viewerName, targetAccount, backAction, navBackAction,
                CirrusInventoryType.GENERIC_9X6, null);
    }

    public BaseInspectMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                           Account targetAccount, @Nullable Consumer<CirrusPlayerWrapper> backAction, CirrusInventoryType inventoryType) {
        this(platform, httpClient, viewerUuid, viewerName, targetAccount, backAction, backAction, inventoryType, null);
    }

    public BaseInspectMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                           Account targetAccount, @Nullable Consumer<CirrusPlayerWrapper> backAction, CirrusInventoryType inventoryType,
                           @Nullable InspectContext inspectContext) {
        this(platform, httpClient, viewerUuid, viewerName, targetAccount, backAction, backAction, inventoryType, inspectContext);
    }

    private BaseInspectMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                            Account targetAccount, @Nullable Consumer<CirrusPlayerWrapper> backAction,
                            @Nullable Consumer<CirrusPlayerWrapper> navBackAction, CirrusInventoryType inventoryType,
                            @Nullable InspectContext inspectContext) {
        super(platform, httpClient, viewerUuid, viewerName, backAction,
                new InspectChrome(platform, httpClient, viewerUuid, viewerName, targetAccount, navBackAction, inspectContext),
                inventoryType);
        this.targetAccount = targetAccount;
        this.targetName = getPlayerName(targetAccount);
        this.targetUuid = targetAccount.getMinecraftUuid();
    }

    protected String getPlayerName(Account account) {
        return ReportRenderUtil.getPlayerName(account);
    }

}
