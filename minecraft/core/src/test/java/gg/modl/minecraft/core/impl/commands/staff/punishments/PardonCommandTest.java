package gg.modl.minecraft.core.impl.commands.staff.punishments;

import gg.modl.minecraft.api.SimplePunishment;
import gg.modl.minecraft.api.http.request.ActionIssuer;
import gg.modl.minecraft.api.http.request.PardonPlayerRequest;
import gg.modl.minecraft.api.http.request.PardonPunishmentRequest;
import gg.modl.minecraft.api.http.response.PardonResponse;
import gg.modl.minecraft.core.CommandAccessPolicy;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.TestHttpClients;
import gg.modl.minecraft.core.boot.CoreState;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.staff.action.StaffActionGate;
import gg.modl.minecraft.core.support.FakeCommandActor;
import gg.modl.minecraft.core.support.FakeModlHttpClient;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.MapLocaleManager;
import gg.modl.minecraft.core.util.Permissions;
import org.junit.jupiter.api.Test;
import revxrsal.commands.Lamp;
import revxrsal.commands.command.CommandActor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PardonCommandTest {
    private static final String TARGET = "RepeatOffender";
    private static final String PUNISHMENT_ID = "MUTE0001";

    @Test
    void eachRootPardonsOnlyItsOwnPunishmentType() {
        List<PardonPlayerRequest> requests = new ArrayList<>();
        HttpClientHolder httpClient = TestHttpClients.connected(recording(requests));
        FakePlatform platform = new FakePlatform();
        Cache cache = new Cache(new CachedProfileRegistry());
        MapLocaleManager localeManager = localeManager();
        Lamp<CommandActor> lamp = gatedLamp(cache, localeManager);
        lamp.register(new PardonCommand(httpClient, platform, cache, localeManager));
        lamp.register(new UnbanCommand(httpClient, platform, cache, localeManager));
        lamp.register(new UnmuteCommand(httpClient, platform, cache, localeManager));
        FakeCommandActor actor = pardoner(cache, UUID.fromString("123e4567-e89b-12d3-a456-426614174401"), "staff-1");

        lamp.dispatch(actor, "pardon " + TARGET);
        lamp.dispatch(actor, "unban " + TARGET);
        lamp.dispatch(actor, "unmute " + TARGET);

        assertEquals(Arrays.asList(TARGET, TARGET, TARGET), playerNames(requests));
        assertEquals(Arrays.asList(null, "ban", "mute"), punishmentTypes(requests));
    }

    @Test
    void pardoningByIdReleasesOnlyThatPunishment() {
        FakeModlHttpClient client = new FakeModlHttpClient() {
            @Override
            public CompletableFuture<PardonResponse> pardonPunishment(PardonPunishmentRequest request) {
                return CompletableFuture.completedFuture(new PardonResponse(null, 200, 1, true));
            }
        };
        CachedProfileRegistry registry = new CachedProfileRegistry();
        CachedProfile pardoned = muted(registry, PUNISHMENT_ID);
        CachedProfile unrelated = muted(registry, "OTHER001");
        Cache cache = new Cache(registry);
        Lamp<CommandActor> lamp = gatedLamp(cache, localeManager());
        lamp.register(new PardonCommand(TestHttpClients.connected(client), new FakePlatform(), cache, localeManager()));

        lamp.dispatch(pardoner(cache, UUID.randomUUID(), "staff-1"), "pardon " + PUNISHMENT_ID);

        assertFalse(pardoned.isMuted());
        assertTrue(unrelated.isMuted());
        assertSame(unrelated, registry.getProfile(unrelated.getUuid()));
    }

    @Test
    void pardonCommandsNeedOnlyThePardonPermissionAndRefuseOtherModifyPermissions() {
        List<PardonPlayerRequest> requests = new ArrayList<>();
        Cache cache = new Cache(new CachedProfileRegistry());
        MapLocaleManager localeManager = localeManager().put("general.no_permission", "No permission");
        Lamp<CommandActor> lamp = gatedLamp(cache, localeManager);
        HttpClientHolder httpClient = TestHttpClients.connected(recording(requests));
        lamp.register(new PardonCommand(httpClient, new FakePlatform(), cache, localeManager));
        lamp.register(new UnbanCommand(httpClient, new FakePlatform(), cache, localeManager));
        lamp.register(new UnmuteCommand(httpClient, new FakePlatform(), cache, localeManager));
        UUID pardoner = UUID.randomUUID();
        UUID durationOnly = UUID.randomUUID();
        cache.cacheStaffPermissions(pardoner, "Pardoner", "staff-1", "Helper",
                Collections.singletonList(Permissions.PUNISHMENT_MODIFY_PARDON));
        cache.cacheStaffPermissions(durationOnly, "Timer", "staff-2", "Helper",
                Collections.singletonList(Permissions.PUNISHMENT_MODIFY_DURATION));

        for (String root : Arrays.asList("pardon", "unban", "unmute")) {
            lamp.dispatch(new FakeCommandActor(durationOnly, "Timer"), root + " " + TARGET);
            lamp.dispatch(new FakeCommandActor(pardoner, "Pardoner"), root + " " + TARGET);
        }

        assertEquals(3, requests.size());
        for (PardonPlayerRequest request : requests) assertEquals("Pardoner", request.getIssuerName());
    }

    @Test
    void aPardonCarriesTheStaffIdTheCommandWasAuthorizedWith() {
        List<PardonPlayerRequest> requests = new ArrayList<>();
        Cache cache = new Cache(new CachedProfileRegistry());
        MapLocaleManager localeManager = localeManager();
        Lamp<CommandActor> lamp = gatedLamp(cache, localeManager);
        lamp.register(new PardonCommand(TestHttpClients.connected(recording(requests)), new FakePlatform(), cache, localeManager));

        lamp.dispatch(pardoner(cache, UUID.randomUUID(), "staff-42"), "pardon " + TARGET);
        lamp.dispatch(new FakeCommandActor(null, "CONSOLE"), "pardon " + TARGET);

        assertEquals(2, requests.size());
        assertEquals(ActionIssuer.staff("staff-42"), requests.get(0).getIssuer());
        assertEquals(ActionIssuer.console(), requests.get(1).getIssuer());
    }

    private static Lamp<CommandActor> gatedLamp(Cache cache, MapLocaleManager localeManager) {
        CommandAccessPolicy policy = new CommandAccessPolicy(
                new StaffActionGate(() -> CoreState.RUNNING, cache, localeManager, null, actor -> false));
        Lamp.Builder<CommandActor> builder = Lamp.builder();
        policy.install(builder);
        return builder.build();
    }

    private static FakeCommandActor pardoner(Cache cache, UUID uuid, String staffId) {
        cache.cacheStaffPermissions(uuid, "Staff", staffId, "Helper",
                Collections.singletonList(Permissions.PUNISHMENT_MODIFY_PARDON));
        return new FakeCommandActor(uuid, "Staff");
    }

    private static CachedProfile muted(CachedProfileRegistry registry, String punishmentId) {
        CachedProfile profile = registry.createProfile(UUID.randomUUID());
        profile.setActiveMute(new SimplePunishment("MUTE", "MUTE", null, "Spam", punishmentId, null, null, null, true, 1, false));
        return profile;
    }

    private static FakeModlHttpClient recording(List<PardonPlayerRequest> requests) {
        return new FakeModlHttpClient() {
            @Override
            public CompletableFuture<PardonResponse> pardonPlayer(PardonPlayerRequest request) {
                requests.add(request);
                return CompletableFuture.completedFuture(new PardonResponse(null, 200, 1, true));
            }
        };
    }

    private static List<String> playerNames(List<PardonPlayerRequest> requests) {
        List<String> names = new ArrayList<>();
        for (PardonPlayerRequest request : requests) names.add(request.getPlayerName());
        return names;
    }

    private static List<String> punishmentTypes(List<PardonPlayerRequest> requests) {
        List<String> types = new ArrayList<>();
        for (PardonPlayerRequest request : requests) types.add(request.getPunishmentType());
        return types;
    }

    private static MapLocaleManager localeManager() {
        return new MapLocaleManager()
                .put("pardon.success_id", "Pardoned #{id}")
                .put("pardon.success_player", "Pardoned {count} {type} of {player}");
    }
}
