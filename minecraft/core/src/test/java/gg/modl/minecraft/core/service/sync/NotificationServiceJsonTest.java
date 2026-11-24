package gg.modl.minecraft.core.service.sync;

import gg.modl.minecraft.core.connection.PanelUrl;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.modl.minecraft.api.http.response.SyncResponse;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.staff.StaffAudiences;
import gg.modl.minecraft.core.support.FakePlatform;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificationServiceJsonTest {
    @Test
    void staffTicketReportNotificationBuildsValidRunCommandJsonWithoutManualEscaping() {
        FakePlatform platform = new FakePlatform();
        LocaleManager localeManager = new LocaleManager();
        NotificationService service = service(platform, localeManager);
        Map<String, Object> data = new HashMap<>();
        data.put("ticketId", "ticket\"\\id\nnext");
        data.put("subject", "Report \"subject\"\\line\nnext");
        data.put("firstReplyContent", "First reply \"quoted\"\\line\nnext");
        data.put("ticketType", "player");
        data.put("category", "player");
        data.put("reportedPlayer", "Target\"\\Name\nNext");
        SyncResponse.StaffNotification notification = new SyncResponse.StaffNotification(
                "notification-1",
                "TICKET_CREATED",
                "New \"ticket\"\\notice\nnext",
                data,
                1L
        );

        service.processStaffNotification(notification);

        JsonObject link = JsonParser.parseString(platform.lastStaffJsonBroadcast())
                .getAsJsonObject()
                .getAsJsonArray("extra")
                .get(0)
                .getAsJsonObject();
        assertEquals("[New \"ticket\"\\notice\nnext]", link.get("text").getAsString());
        assertEquals("run_command", link.getAsJsonObject("clickEvent").get("action").getAsString());
        assertEquals("/target Target\"\\Name\nNext", link.getAsJsonObject("clickEvent").get("value").getAsString());
        String targetHint = localeManager.getMessageWithRawValues("staff_notifications.click_to_target",
                Collections.singletonMap("player", "Target\"\\Name\nNext"));
        assertEquals("Report \"subject\"\\line\nnext\n\nFirst reply \"quoted\"\\line\nnext\n\n" + targetHint,
                link.getAsJsonObject("hoverEvent").get("value").getAsString());
    }

    @Test
    void aChatReportAlertOpensTheTicketInsteadOfTargetingThePlayer() {
        FakePlatform platform = new FakePlatform();
        NotificationService service = service(platform, new LocaleManager());
        Map<String, Object> data = new HashMap<>();
        data.put("ticketId", "CHAT-1");
        data.put("ticketType", "chat");
        data.put("category", "chat");
        data.put("reportedPlayer", "Steve");

        service.processStaffNotification(new SyncResponse.StaffNotification(
                "ticket_CHAT-1", "TICKET_CREATED", "Alex: reported Steve (Chat)", data, 1L));

        JsonObject click = JsonParser.parseString(platform.lastStaffJsonBroadcast())
                .getAsJsonObject()
                .getAsJsonArray("extra")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("clickEvent");
        assertEquals("open_url", click.get("action").getAsString());
        assertEquals("https://panel.example.com/panel/tickets/CHAT-1", click.get("value").getAsString());
    }

    private static NotificationService service(FakePlatform platform, LocaleManager localeManager) {
        return new NotificationService(
                platform,
                null,
                null,
                null,
                localeManager,
                PanelUrl.of("https://panel.example.com"),
                new StaffAudiences(uuid -> true, new Cache(new CachedProfileRegistry())),
                false
        );
    }
}
