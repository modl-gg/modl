package gg.modl.minecraft.core.impl.menus.util;

import dev.simplix.cirrus.menu.DisplayedMenu;
import dev.simplix.cirrus.model.CirrusClickType;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.service.MenuBuildService;
import gg.modl.minecraft.api.Punishment;
import gg.modl.minecraft.api.SimplePunishment;
import gg.modl.minecraft.api.http.ApiClientException;
import gg.modl.minecraft.api.http.request.AddPunishmentNoteRequest;
import gg.modl.minecraft.api.http.request.ChangePunishmentDurationRequest;
import gg.modl.minecraft.api.http.request.ModifyPunishmentTicketsRequest;
import gg.modl.minecraft.api.http.request.PardonPunishmentRequest;
import gg.modl.minecraft.api.http.request.TogglePunishmentOptionRequest;
import gg.modl.minecraft.api.http.response.PardonResponse;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.support.FakeModlHttpClient;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.TestPluginServices;
import gg.modl.minecraft.core.util.ApiFailures;
import gg.modl.minecraft.core.util.Permissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentModificationActionsTest {
    private static final String MUTE_ID = "MUTE0001";
    private static final String OTHER_ID = "OTHER001";
    private static final long ONE_HOUR = 3_600_000L;

    private final UUID staff = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final FakePlatform platform = new FakePlatform();
    private final CachedProfileRegistry registry = new CachedProfileRegistry();
    private final Cache cache = new Cache(registry);
    private final CachedProfile profile = registry.createProfile(target);
    private final RecordingClient client = new RecordingClient();
    private PluginServices services;

    @BeforeEach
    void installServices() {
        LocaleManager locale = new LocaleManager();
        services = TestPluginServices.install(cache, locale, TestPluginServices.runningGate(cache, locale),
                new ChatInputManager(platform, locale));
        profile.setActiveMute(new SimplePunishment("MUTE", "MUTE", null, "Spam", MUTE_ID, null, null, null, true, 1, false));
    }

    @AfterEach
    void uninstallServices() {
        PluginServices.uninstall(services);
    }

    @Test
    void pardoningTheCachedMuteReleasesIt() {
        grant(Permissions.PUNISHMENT_MODIFY, Permissions.PUNISHMENT_MODIFY_PARDON);

        actions(MUTE_ID).handlePardon(click());

        assertFalse(profile.isMuted());
    }

    @Test
    void pardoningAnotherPunishmentKeepsTheMute() {
        grant(Permissions.PUNISHMENT_MODIFY, Permissions.PUNISHMENT_MODIFY_PARDON);

        actions(OTHER_ID).handlePardon(click());

        assertTrue(profile.isMuted());
    }

    @Test
    void changingTheDurationOfTheCachedMuteMovesItsExpiryWithoutReleasingIt() {
        grant(Permissions.PUNISHMENT_MODIFY, Permissions.PUNISHMENT_MODIFY_DURATION, Permissions.PUNISHMENT_MODIFY_PARDON);
        long before = System.currentTimeMillis();

        changeDuration(MUTE_ID);

        assertTrue(profile.isMuted());
        assertTrue(profile.getActiveMute().getExpiration() >= before + ONE_HOUR);
    }

    @Test
    void changingTheDurationOfAnotherPunishmentKeepsTheMuteUnchanged() {
        grant(Permissions.PUNISHMENT_MODIFY, Permissions.PUNISHMENT_MODIFY_DURATION, Permissions.PUNISHMENT_MODIFY_PARDON);

        changeDuration(OTHER_ID);

        assertTrue(profile.isMuted());
        assertTrue(profile.getActiveMute().isPermanent());
    }

    @Test
    void staffWithoutThePardonPermissionCannotPardonFromAnyMenu() {
        grant(Permissions.TICKET_VIEW_ALL, Permissions.PUNISHMENT_MODIFY_DURATION);

        actions(MUTE_ID).handlePardon(click());

        assertTrue(client.pardons.isEmpty());
        assertTrue(profile.isMuted());
    }

    @Test
    void aSubPermissionAllowsOnlyItsOwnAction() {
        grant(Permissions.PUNISHMENT_MODIFY_PARDON);
        PunishmentModificationActions actions = actions(MUTE_ID);

        actions.handleToggleStatWipe(click());
        actions.handleToggleAltBlock(click());
        actions.handleAddNote(click());
        actions.handlePardon(click());

        assertTrue(client.toggles.isEmpty());
        assertEquals(1, client.pardons.size());
    }

    @Test
    void aDurationTypedAfterTheStaffMemberWasDemotedIsNotApplied() {
        grant(Permissions.PUNISHMENT_MODIFY_DURATION);
        actions(MUTE_ID).handleChangeDuration(click());

        cache.removeStaffPermissions(staff);
        PluginServices.chatInput().handleChat(staff, "1h");

        assertTrue(client.durationChanges.isEmpty());
        assertTrue(profile.getActiveMute().isPermanent());
    }

    @Test
    void aNoteTypedAfterTheStaffMemberWasDemotedIsNotAdded() {
        grant(Permissions.PUNISHMENT_MODIFY_NOTE);
        actions(MUTE_ID).handleAddNote(click());

        cache.removeStaffPermissions(staff);
        PluginServices.chatInput().handleChat(staff, "note text");

        assertTrue(client.notes.isEmpty());
    }

    @Test
    void linkedTicketsChosenAfterTheStaffMemberWasDemotedAreNotSaved() {
        grant(Permissions.PUNISHMENT_MODIFY_TICKETS);
        PunishmentModificationActions actions = actions(MUTE_ID);

        cache.removeStaffPermissions(staff);
        actions.saveLinkedTickets(click(), Collections.singleton("TICKET01"), player -> { });

        assertTrue(client.ticketChanges.isEmpty());
    }

    @Test
    void linkedTicketsAreSavedWithThePermission() {
        grant(Permissions.PUNISHMENT_MODIFY_TICKETS);

        actions(MUTE_ID).saveLinkedTickets(click(), Collections.singleton("TICKET01"), player -> { });

        assertEquals(1, client.ticketChanges.size());
        assertEquals(Collections.singletonList("TICKET01"), client.ticketChanges.get(0).getAddTicketIds());
    }

    @Test
    void anOptionChangeTheBackendRefusesShowsTheLocalizedPermissionMessage() {
        grant(Permissions.PUNISHMENT_MODIFY_OPTIONS);
        List<String> messages = new ArrayList<>();
        FakeModlHttpClient refusing = new FakeModlHttpClient() {
            @Override
            public CompletableFuture<Void> togglePunishmentOption(TogglePunishmentOptionRequest request) {
                CompletableFuture<Void> refused = new CompletableFuture<>();
                refused.completeExceptionally(new ApiClientException(403, "You do not have permission to make this change"));
                return refused;
            }
        };

        new PunishmentModificationActions(platform, refusing, staff, "Staff", target,
                Punishment.builder().id(MUTE_ID).build(), messages::add, refreshed -> { }, player -> { })
                .handleToggleStatWipe(click());

        assertEquals(Collections.singletonList(PluginServices.locale().getMessage("general.no_permission")), messages);
    }

    @Test
    void theDurationPermissionAloneCannotShortenAndSaysWhy() {
        grant(Permissions.PUNISHMENT_MODIFY_DURATION);
        List<String> messages = new ArrayList<>();

        actionsFor(Punishment.builder().id(MUTE_ID).typeOrdinal(1).started(Instant.now()).build(), messages).handleChangeDuration(click());
        PluginServices.chatInput().handleChat(staff, "1h");

        assertTrue(client.durationChanges.isEmpty());
        assertTrue(messages.contains(PluginServices.locale().getMessage(ApiFailures.SHORTENING_NEEDS_PARDON_KEY)));
    }

    @Test
    void theDurationPermissionAloneMayLengthen() {
        grant(Permissions.PUNISHMENT_MODIFY_DURATION);
        Punishment oneHour = Punishment.builder().id(MUTE_ID).typeOrdinal(1).started(Instant.now())
                .dataMap(Collections.singletonMap("duration", (Object) ONE_HOUR)).build();

        actionsFor(oneHour, new ArrayList<>()).handleChangeDuration(click());
        PluginServices.chatInput().handleChat(staff, "2h");

        assertEquals(1, client.durationChanges.size());
    }

    @Test
    void durationAndPardonTogetherMayShorten() {
        grant(Permissions.PUNISHMENT_MODIFY_DURATION, Permissions.PUNISHMENT_MODIFY_PARDON);

        actionsFor(Punishment.builder().id(MUTE_ID).typeOrdinal(1).started(Instant.now()).build(), new ArrayList<>()).handleChangeDuration(click());
        PluginServices.chatInput().handleChat(staff, "1h");

        assertEquals(1, client.durationChanges.size());
    }

    @Test
    void aShorteningTheBackendRefusesShowsItsReason() {
        grant(Permissions.PUNISHMENT_MODIFY_OPTIONS);
        List<String> messages = new ArrayList<>();
        FakeModlHttpClient refusing = new FakeModlHttpClient() {
            @Override
            public CompletableFuture<Void> togglePunishmentOption(TogglePunishmentOptionRequest request) {
                CompletableFuture<Void> refused = new CompletableFuture<>();
                refused.completeExceptionally(new ApiClientException(403, "refused", ApiFailures.SHORTENING_NEEDS_PARDON));
                return refused;
            }
        };

        new PunishmentModificationActions(platform, refusing, staff, "Staff", target,
                Punishment.builder().id(MUTE_ID).build(), messages::add, refreshed -> { }, player -> { })
                .handleToggleAltBlock(click());

        assertEquals(Collections.singletonList(PluginServices.locale().getMessage(ApiFailures.SHORTENING_NEEDS_PARDON_KEY)),
                messages);
    }

    private PunishmentModificationActions actionsFor(Punishment punishment, List<String> messages) {
        return new PunishmentModificationActions(platform, client, staff, "Staff", target,
                punishment, messages::add, refreshed -> { }, player -> { });
    }

    private void grant(String... permissions) {
        cache.cacheStaffPermissions(staff, "Staff", "staff-1", "Helper", Arrays.asList(permissions));
    }

    private void changeDuration(String punishmentId) {
        actions(punishmentId).handleChangeDuration(click());
        PluginServices.chatInput().handleChat(staff, "1h");
    }

    private PunishmentModificationActions actions(String punishmentId) {
        return new PunishmentModificationActions(platform, client, staff, "Staff", target,
                Punishment.builder().id(punishmentId).build(), message -> { }, refreshed -> { }, player -> { });
    }

    private static Click click() {
        MenuBuildService closingService = (MenuBuildService) Proxy.newProxyInstance(MenuBuildService.class.getClassLoader(),
                new Class<?>[]{MenuBuildService.class}, (proxy, method, arguments) -> null);
        return new Click(CirrusClickType.LEFT_CLICK,
                new DisplayedMenu(null, null, null, closingService, 1L, new AtomicBoolean()), null, 0);
    }

    private static final class RecordingClient extends FakeModlHttpClient {
        private final List<PardonPunishmentRequest> pardons = new ArrayList<>();
        private final List<ChangePunishmentDurationRequest> durationChanges = new ArrayList<>();
        private final List<TogglePunishmentOptionRequest> toggles = new ArrayList<>();
        private final List<AddPunishmentNoteRequest> notes = new ArrayList<>();
        private final List<ModifyPunishmentTicketsRequest> ticketChanges = new ArrayList<>();

        @Override
        public CompletableFuture<PardonResponse> pardonPunishment(PardonPunishmentRequest request) {
            pardons.add(request);
            return CompletableFuture.completedFuture(new PardonResponse(null, 200, 1, true));
        }

        @Override
        public CompletableFuture<Void> changePunishmentDuration(ChangePunishmentDurationRequest request) {
            durationChanges.add(request);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> togglePunishmentOption(TogglePunishmentOptionRequest request) {
            toggles.add(request);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> addPunishmentNote(AddPunishmentNoteRequest request) {
            notes.add(request);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> modifyPunishmentTickets(ModifyPunishmentTicketsRequest request) {
            ticketChanges.add(request);
            return CompletableFuture.completedFuture(null);
        }
    }
}
