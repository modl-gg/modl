package gg.modl.minecraft.core.service.sync;

import gg.modl.minecraft.api.http.response.SyncResponse;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.staff.StaffAudiences;
import gg.modl.minecraft.core.support.FakePlatform;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaffNotificationDedupeTest {
    private final FakePlatform platform = new FakePlatform();
    private final NotificationService service = new NotificationService(platform, null, null, null, new LocaleManager(),
            PanelUrl.of("https://panel.example.com"), new StaffAudiences(uuid -> true, new Cache(new CachedProfileRegistry())), false);

    @Test
    void aNotificationRedeliveredByTheSyncOverlapIsBroadcastOnce() {
        SyncResponse.StaffNotification issued = notification("punishment_ABC12345", "Mod: banned Griefer (Griefing)");
        SyncResponse.StaffNotification pardoned = notification("pardon_ABC12345", "Mod: pardoned Griefer's ban");

        service.processStaffNotification(issued);
        service.processStaffNotification(pardoned);
        service.processStaffNotification(notification("punishment_ABC12345", "Mod: banned Griefer (Griefing)"));
        service.processStaffNotification(notification("pardon_ABC12345", "Mod: pardoned Griefer's ban"));

        assertEquals(Arrays.asList("&7&o[Mod: banned Griefer (Griefing)&7&o]", "&7&o[Mod: pardoned Griefer's ban&7&o]"),
                platform.staffBroadcasts());
    }

    @Test
    void notificationsWithoutAnIdAreNeverSuppressed() {
        service.processStaffNotification(notification("", "Console: kicked Spammer (Spam)"));
        service.processStaffNotification(notification(null, "Console: kicked Spammer (Spam)"));

        assertEquals(2, platform.staffBroadcasts().size());
    }

    private static SyncResponse.StaffNotification notification(String id, String message) {
        return new SyncResponse.StaffNotification(id, "PUNISHMENT_ISSUED", message, null, 1L);
    }
}
