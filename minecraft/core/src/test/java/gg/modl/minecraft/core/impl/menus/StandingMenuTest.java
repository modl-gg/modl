package gg.modl.minecraft.core.impl.menus;

import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.api.Note;
import gg.modl.minecraft.api.Punishment;
import gg.modl.minecraft.api.http.response.PlayerStandingResponse;
import gg.modl.minecraft.api.http.response.PlayerStandingResponse.Entry;
import gg.modl.minecraft.api.http.response.PlayerStandingResponse.State;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.config.ConfigManager.StandingGuiConfig;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.support.FakeModlHttpClient;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.TestPluginServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandingMenuTest {
    private static final String STAFF_NOTE = "suspected alt of Herobrine";
    private static final String ISSUED_REASON = "Spamming in public chat";
    private static final String PLAYER_REASON = "Follow the server rules";
    private static final String DEFAULT_REASON = "No reason given";
    private static final int BAD_USERNAME = 10;
    private static final long MINUTE = 60 * 1000L;
    private static final long SEVEN_DAYS = 7 * 24 * 60 * MINUTE;

    private final UUID viewer = UUID.randomUUID();
    private LocaleManager locale;

    @BeforeEach
    void installServices() {
        locale = new LocaleManager();
        locale.setConfigValues(Collections.singletonMap("default_reason", DEFAULT_REASON));
        TestPluginServices.install(new Cache(new CachedProfileRegistry()), locale);
    }

    @Test
    void theReasonTheBackendChoseForThePlayerIsShown() {
        assertTrue(lore(entry(State.ACTIVE, null, null, PLAYER_REASON)).contains(PLAYER_REASON));
    }

    @Test
    void withoutAReasonThePlayerSeesTheLocalizedDefault() {
        assertTrue(lore(entry(State.ACTIVE, null, null, null)).contains(DEFAULT_REASON));
    }

    @Test
    void aTimedPunishmentShowsItsDuration() {
        assertTrue(lore(entry(State.ACTIVE, null, null, PLAYER_REASON)).contains("7d"));
    }

    @Test
    void aQueuedPunishmentSaysSo() {
        assertShowsStatus(entry(State.QUEUED, null, null, PLAYER_REASON), "queued");
    }

    @Test
    void aPardonedPunishmentSaysSo() {
        assertShowsStatus(entry(State.PARDONED, System.currentTimeMillis() - MINUTE, null, PLAYER_REASON), "pardoned");
    }

    @Test
    void aRolledBackPunishmentSaysSo() {
        assertShowsStatus(entry(State.ROLLED_BACK, System.currentTimeMillis() - MINUTE, null, PLAYER_REASON), "rolled back");
    }

    @Test
    void theStaffDisplayStillFallsBackToTheNote() {
        assertEquals(STAFF_NOTE, staffPunishment(null).getStaffDisplayReason());
    }

    @Test
    void theStaffDisplayPrefersTheIssuedReason() {
        assertEquals(ISSUED_REASON, staffPunishment(ISSUED_REASON).getStaffDisplayReason());
    }

    private void assertShowsStatus(Entry entry, String status) {
        CirrusItem item = item(entry);
        String shown = item.displayName().asLegacyText() + "\n" + loreOf(item);
        assertTrue(shown.contains(status), shown);
    }

    private String lore(Entry entry) {
        return loreOf(item(entry));
    }

    private static String loreOf(CirrusItem item) {
        List<CirrusChatElement> lore = item.lore();
        return lore.stream().map(CirrusChatElement::asLegacyText).collect(Collectors.joining("\n"));
    }

    private CirrusItem item(Entry entry) {
        PlayerStandingResponse standing =
                new PlayerStandingResponse("Low", "Low", 0, 0, Collections.singletonList(entry));
        return new StandingMenu(new FakePlatform(), new FakeModlHttpClient(), viewer, "Player",
                standing, new StandingGuiConfig(), locale).mapItem(entry);
    }

    private static Entry entry(State state, Long endedAt, Long expiresAt, String reason) {
        return new Entry("ABCD1234", "Chat Abuse", "MUTE", System.currentTimeMillis() - MINUTE,
                SEVEN_DAYS, state, endedAt, expiresAt, reason);
    }

    private static Punishment staffPunishment(String reason) {
        Map<String, Object> data = new HashMap<>();
        if (reason != null) data.put("reason", reason);
        Instant issued = Instant.ofEpochMilli(System.currentTimeMillis() - 1_000L);
        return Punishment.builder()
                .id("ABCD1234")
                .issued(issued)
                .typeOrdinal(BAD_USERNAME)
                .dataMap(data)
                .notes(Collections.singletonList(new Note(STAFF_NOTE, issued, "Admin", null)))
                .build();
    }
}
