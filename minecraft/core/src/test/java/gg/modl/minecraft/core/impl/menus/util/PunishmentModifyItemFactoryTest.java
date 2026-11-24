package gg.modl.minecraft.core.impl.menus.util;

import dev.simplix.cirrus.item.CirrusItem;
import gg.modl.minecraft.api.Punishment;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.staff.action.StaffRequirement;
import gg.modl.minecraft.core.staff.action.StaffRequirements;
import gg.modl.minecraft.core.support.TestPluginServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentModifyItemFactoryTest {
    private static final long ONE_HOUR = 3_600_000L;
    private static final String CHANGE_DURATION = "changeDuration";

    private final Cache cache = new Cache(new CachedProfileRegistry());
    private PluginServices services;

    @BeforeEach
    void install() {
        LocaleManager locale = new LocaleManager();
        services = TestPluginServices.install(cache, locale, TestPluginServices.runningGate(cache, locale), null);
    }

    @AfterEach
    void uninstall() {
        PluginServices.uninstall(services);
    }

    @Test
    void aPermanentPunishmentOffersNoDurationChangeToStaffWhoCannotShorten() {
        CirrusItem item = durationItem(permanent(), only(StaffRequirements.CHANGE_PUNISHMENT_DURATION));

        assertEquals("noAction", item.actionHandler());
        assertTrue(loreContains(item, PluginServices.locale().getMessage("menus.modify_punishment.duration.needs_pardon")));
    }

    @Test
    void aTimedPunishmentIsOfferedForLengtheningAndSaysSo() {
        CirrusItem item = durationItem(oneHour(), only(StaffRequirements.CHANGE_PUNISHMENT_DURATION));

        assertEquals(CHANGE_DURATION, item.actionHandler());
        assertTrue(loreContains(item, PluginServices.locale().getMessage("menus.modify_punishment.duration.lengthen_only")));
    }

    @Test
    void durationWithPardonIsOfferedWithoutTheLimit() {
        CirrusItem item = durationItem(permanent(),
                only(StaffRequirements.CHANGE_PUNISHMENT_DURATION, StaffRequirements.PARDON_PUNISHMENT));

        assertEquals(CHANGE_DURATION, item.actionHandler());
    }

    private static CirrusItem durationItem(Punishment punishment, Predicate<StaffRequirement> allows) {
        return new PunishmentModifyItemFactory(punishment).itemsFor(allows).stream()
                .filter(item -> item.slot() == MenuSlots.MODIFY_DURATION)
                .findFirst()
                .orElseThrow(IllegalStateException::new);
    }

    private static boolean loreContains(CirrusItem item, String line) {
        StringBuilder joined = new StringBuilder();
        item.lore().forEach(element -> joined.append(' ').append(element.asLegacyText()));
        return plain(joined.toString()).contains(plain(line));
    }

    private static String plain(String text) {
        return text.replaceAll("[\u00a7&].", "").replaceAll("\\s+", " ").trim();
    }

    private static Predicate<StaffRequirement> only(StaffRequirement... granted) {
        List<StaffRequirement> allowed = Arrays.asList(granted);
        return allowed::contains;
    }

    private static Punishment permanent() {
        return Punishment.builder().id("MUTE0001").typeOrdinal(1).started(Instant.now()).build();
    }

    private static Punishment oneHour() {
        return Punishment.builder().id("MUTE0001").typeOrdinal(1).started(Instant.now())
                .dataMap(Collections.singletonMap("duration", (Object) ONE_HOUR)).build();
    }
}
