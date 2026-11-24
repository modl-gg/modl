package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.core.boot.CoreState;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.network.ModerationSessionService;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.MapLocaleManager;
import gg.modl.minecraft.core.util.Permissions;

import java.util.Collections;
import java.util.UUID;

public final class StaffActionFixture {
    public final FakePlatform platform;
    public final Cache cache = new Cache(new CachedProfileRegistry());
    public final MapLocaleManager locale = new MapLocaleManager().withFallback(path -> path);
    public final ModerationSessionService moderation = new ModerationSessionService();
    public final StaffActionContext context;
    public final StaffActionGate gate = new StaffActionGate(() -> CoreState.RUNNING, cache, locale, null, actor -> false);

    public StaffActionFixture() {
        this(new FakePlatform());
    }

    public StaffActionFixture(FakePlatform platform) {
        this.platform = platform;
        this.context = new StaffActionContext(platform, cache, locale, moderation);
    }

    public AbstractPlayer player(String name) {
        AbstractPlayer player = new AbstractPlayer(UUID.randomUUID(), name, true);
        platform.register(player);
        return player;
    }

    public AbstractPlayer moderator(String name) {
        AbstractPlayer player = player(name);
        cache.cacheStaffPermissions(player.getUuid(), name, "staff-" + name, "Moderator",
                Collections.singletonList(Permissions.MOD_ACTIONS));
        return player;
    }

    public StaffActor actor(AbstractPlayer player) {
        return StaffActors.player(player.getUuid(), platform);
    }

    public String lastMessageTo(AbstractPlayer player) {
        String last = null;
        for (FakePlatform.Message message : platform.sentMessages()) {
            if (message.uuid().equals(player.getUuid())) last = message.message();
        }
        return last;
    }
}
