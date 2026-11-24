package gg.modl.minecraft.core.service.sync;

import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.http.response.SyncResponse;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.staff.PermissionUtil;
import gg.modl.minecraft.core.staff.StaffAudiences;
import gg.modl.minecraft.core.staff.StaffNoticeType;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.util.Permissions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaffNotificationRecipientsTest {
    private final FakePlatform platform = new FakePlatform();
    private final Cache cache = new Cache(new CachedProfileRegistry());
    private final UUID viewer = online("Viewer", Permissions.PUNISHMENT_VIEW);
    private final UUID applier = online("Applier", Permissions.PUNISHMENT_APPLY_PREFIX + "2");
    private final UUID ticketHandler = online("Tickets", Permissions.TICKET_VIEW_ALL);
    private final UUID defaultHelper = online("Helper", Permissions.PUNISHMENT_VIEW, Permissions.TICKET_VIEW_ALL);
    {
        online("Player");
    }
    private final NotificationService service = new NotificationService(platform, null, cache, null, new LocaleManager(),
            PanelUrl.of("https://panel.example.com"),
            new StaffAudiences(uuid -> PermissionUtil.isStaff(uuid, cache), cache), false);

    @Test
    void punishmentAndPardonLinesReachOnlyStaffWhoMayViewPlayers() {
        service.processStaffNotification(new SyncResponse.StaffNotification("punishment_1", "PUNISHMENT_ISSUED",
                "Mod: banned Griefer (Griefing)", null, 1L));
        service.processStaffNotification(new SyncResponse.StaffNotification("pardon_1", "PUNISHMENT_PARDONED",
                "Mod: pardoned Griefer's ban", null, 2L));

        assertEquals(set(viewer, applier, defaultHelper), recipientsOf(platform.sentMessages().stream()
                .map(FakePlatform.Message::uuid).collect(Collectors.toList()), 2));
    }

    @Test
    void ticketAndReportAlertsReachOnlyStaffWhoMayViewTickets() {
        Map<String, Object> data = new HashMap<>();
        data.put("ticketId", "T-1");
        data.put("subject", "Cheater");
        data.put("firstReplyContent", "He flies");
        data.put("ticketType", "player");
        data.put("category", "player");
        data.put("reportedPlayer", "Griefer");

        service.processStaffNotification(new SyncResponse.StaffNotification("ticket_T-1", "TICKET_CREATED",
                "Reporter: reported Griefer", data, 1L));

        assertEquals(set(ticketHandler, defaultHelper), recipientsOf(platform.sentJsonMessages().stream()
                .map(FakePlatform.JsonMessage::uuid).collect(Collectors.toList()), 1));
    }

    @Test
    void aTicketAlertWithoutDataStillReachesOnlyStaffWhoMayViewTickets() {
        service.processStaffNotification(new SyncResponse.StaffNotification("ticket_T-2", "TICKET_CREATED",
                "Reporter: reported Griefer", null, 1L));

        assertEquals(set(ticketHandler, defaultHelper), recipientsOf(platform.sentMessages().stream()
                .map(FakePlatform.Message::uuid).collect(Collectors.toList()), 1));
    }

    @Test
    void aNoticeOfAnUnknownTypeReachesNoOne() {
        service.processStaffNotification(new SyncResponse.StaffNotification("other_1", "SOMETHING_ELSE", "note", null, 1L));

        assertEquals(0, platform.sentMessages().size());
        assertEquals(0, platform.sentJsonMessages().size());
    }

    @Test
    void everyNoticeTypeTheBackendSendsHasAnAudience() {
        assertEquals(set("TICKET_CREATED", "PUNISHMENT_ISSUED", "PUNISHMENT_PARDONED"),
                Arrays.stream(StaffNoticeType.values()).map(Enum::name).collect(Collectors.toSet()));
    }

    private UUID online(String name, String... permissions) {
        UUID uuid = UUID.randomUUID();
        platform.register(new AbstractPlayer(uuid, name, null, true));
        if (permissions.length > 0) {
            cache.cacheStaffPermissions(uuid, name, "staff-" + name, "Role", Arrays.asList(permissions));
        }
        return uuid;
    }

    @SafeVarargs
    private static <T> Set<T> set(T... values) {
        Set<T> set = new HashSet<>();
        for (T value : values) set.add(value);
        return set;
    }

    private static Set<UUID> recipientsOf(List<UUID> deliveries, int messagesEach) {
        Map<UUID, Long> counts = deliveries.stream().collect(Collectors.groupingBy(uuid -> uuid, Collectors.counting()));
        for (long count : counts.values()) assertEquals(messagesEach, count);
        return counts.keySet();
    }
}
