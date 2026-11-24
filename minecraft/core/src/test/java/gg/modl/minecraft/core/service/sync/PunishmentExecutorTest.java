package gg.modl.minecraft.core.service.sync;

import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.Modification;
import gg.modl.minecraft.api.SimplePunishment;
import gg.modl.minecraft.api.http.response.SyncResponse;
import gg.modl.minecraft.api.http.request.PunishmentAcknowledgeRequest;
import gg.modl.minecraft.core.TestHttpClients;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.punishment.PunishmentMessageService;
import gg.modl.minecraft.core.support.FakeModlHttpClient;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.MapLocaleManager;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentExecutorTest {
    private static final String MUTE_ID = "MUTE0001";
    private static final long MODIFIED_AT = System.currentTimeMillis();
    private static final long DURATION = 3_600_000L;

    private final UUID player = UUID.randomUUID();
    private final CachedProfileRegistry registry = new CachedProfileRegistry();
    private final CachedProfile profile = registry.createProfile(player);
    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private final PunishmentExecutor executor = new PunishmentExecutor(new FakePlatform(), null, new Cache(registry),
            logger, null, false);

    PunishmentExecutorTest() {
        profile.setActiveMute(new SimplePunishment("MUTE", "MUTE", null, "Spam", MUTE_ID, null, null, null, true, 1, false));
    }

    @ParameterizedTest
    @EnumSource(value = Modification.Type.class, names = {"MANUAL_PARDON", "APPEAL_ACCEPT", "SYSTEM_PARDON", "ROLLBACK"})
    void anEndingModificationReleasesAConnectedMutedPlayer(Modification.Type type) {
        modify(MUTE_ID, type);

        assertFalse(profile.isMuted());
    }

    @Test
    void aRejectedAppealKeepsTheMute() {
        modify(MUTE_ID, Modification.Type.APPEAL_REJECT);

        assertTrue(profile.isMuted());
    }

    @Test
    void rollingBackAnotherPunishmentKeepsTheMute() {
        modify("OTHER001", Modification.Type.ROLLBACK);

        assertTrue(profile.isMuted());
    }

    @Test
    void anAppealDurationChangeMovesTheCachedExpiry() {
        modify(MUTE_ID, Modification.Type.APPEAL_DURATION_CHANGE, DURATION);

        assertEquals(MODIFIED_AT + DURATION, profile.getActiveMute().getExpiration());
    }

    @Test
    void anAppealDurationChangeToPermanentClearsTheCachedExpiry() {
        profile.getActiveMute().setExpiration(MODIFIED_AT + DURATION);

        modify(MUTE_ID, Modification.Type.APPEAL_DURATION_CHANGE, 0L);

        assertNull(profile.getActiveMute().getExpiration());
    }

    @Test
    void anAppealRejectionLeavesTheMuteWithoutAWarning() {
        modify(MUTE_ID, Modification.Type.APPEAL_REJECT, 0L);

        assertTrue(profile.isMuted());
        assertNull(profile.getActiveMute().getExpiration());
        assertEquals(Collections.emptyList(), logger.warnings());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SET_ALT_BLOCKING_TRUE", "SET_ALT_BLOCKING_FALSE", "SET_WIPING_TRUE", "SET_WIPING_FALSE"})
    void anOptionChangeLeavesTheMuteAndItsExpiryWithoutAWarning(String type) {
        profile.getActiveMute().setExpiration(MODIFIED_AT + DURATION);

        executor.processModifiedPunishment(new SyncResponse.ModifiedPunishment(player.toString(), "Player",
                new SyncResponse.PunishmentWithModifications(MUTE_ID, Collections.singletonList(
                        new SyncResponse.PunishmentModification(type, MODIFIED_AT, 0L)))));

        assertTrue(profile.isMuted());
        assertEquals(MODIFIED_AT + DURATION, profile.getActiveMute().getExpiration());
        assertEquals(Collections.emptyList(), logger.warnings());
    }

    @Test
    void aPendingBanDeliveredTwiceByOverlappingSyncsIsEnforcedOnce() {
        FakePlatform platform = new FakePlatform();
        List<PunishmentAcknowledgeRequest> acknowledgements = new ArrayList<>();
        PunishmentExecutor enforcing = enforcingExecutor(platform, acknowledgements);
        SyncResponse.PendingPunishment ban = new SyncResponse.PendingPunishment(player.toString(), "Player",
                new SimplePunishment("BAN", "BAN", null, "Cheating", "BAN00001", null, null, null, false, 2, false));

        enforcing.processPendingPunishment(ban);
        enforcing.processPendingPunishment(ban);

        assertEquals(1, platform.broadcasts().size());
        assertEquals(2, acknowledgements.size());
        for (PunishmentAcknowledgeRequest acknowledgement : acknowledgements) assertTrue(acknowledgement.isSuccess());
    }

    @Test
    void aSilentPunishmentIsEnforcedWithoutAPublicBroadcast() {
        FakePlatform platform = new FakePlatform();
        List<PunishmentAcknowledgeRequest> acknowledgements = new ArrayList<>();
        UUID muted = UUID.randomUUID();
        CachedProfile mutedProfile = registry.createProfile(muted);

        enforcingExecutor(platform, acknowledgements).processPendingPunishment(new SyncResponse.PendingPunishment(
                muted.toString(), "Quiet", new SimplePunishment("MUTE", "MUTE", null, "Spam", "MUTE0002",
                        null, null, null, true, 1, true)));

        assertTrue(mutedProfile.isMuted());
        assertEquals(Collections.emptyList(), platform.broadcasts());
        assertTrue(acknowledgements.get(0).isSuccess());
    }

    @Test
    void aMutedPlayerWhoIsOnlineIsToldAtOnce() {
        UUID muted = UUID.randomUUID();
        FakePlatform platform = new FakePlatform().register(new AbstractPlayer(muted, "Loud", true));
        registry.createProfile(muted);

        enforcingExecutor(platform, new ArrayList<>()).processPendingPunishment(new SyncResponse.PendingPunishment(
                muted.toString(), "Loud", new SimplePunishment("MUTE", "MUTE", null, "Spam", "MUTE0003",
                        null, null, null, true, 1, true)));

        assertEquals(1, platform.sentMessages().size());
        assertEquals(muted, platform.sentMessages().get(0).uuid());
    }

    @Test
    void aMutedPlayerWhoIsOfflineIsNotMessaged() {
        UUID muted = UUID.randomUUID();
        FakePlatform platform = new FakePlatform();
        registry.createProfile(muted);

        enforcingExecutor(platform, new ArrayList<>()).processPendingPunishment(new SyncResponse.PendingPunishment(
                muted.toString(), "Away", new SimplePunishment("MUTE", "MUTE", null, "Spam", "MUTE0004",
                        null, null, null, true, 1, true)));

        assertEquals(Collections.emptyList(), platform.sentMessages());
    }

    @Test
    void aPendingMuteThatIsAlreadyTheCachedActiveMuteIsNotAnnouncedAgain() {
        FakePlatform platform = new FakePlatform();
        List<PunishmentAcknowledgeRequest> acknowledgements = new ArrayList<>();

        enforcingExecutor(platform, acknowledgements).processPendingPunishment(new SyncResponse.PendingPunishment(
                player.toString(), "Player", profile.getActiveMute()));

        assertEquals(Collections.emptyList(), platform.broadcasts());
        assertEquals(1, acknowledgements.size());
        assertTrue(acknowledgements.get(0).isSuccess());
    }

    private PunishmentExecutor enforcingExecutor(FakePlatform platform, List<PunishmentAcknowledgeRequest> acknowledgements) {
        FakeModlHttpClient client = new FakeModlHttpClient() {
            @Override
            public CompletableFuture<Void> acknowledgePunishment(PunishmentAcknowledgeRequest request) {
                acknowledgements.add(request);
                return CompletableFuture.completedFuture(null);
            }
        };
        MapLocaleManager locale = new MapLocaleManager().withFallback(path -> path);
        return new PunishmentExecutor(platform, TestHttpClients.connected(client), new Cache(registry), logger,
                new PunishmentMessageService(locale,
                        PanelUrl.of("https://panel.example.com")), false);
    }

    private void modify(String punishmentId, Modification.Type type) {
        modify(punishmentId, type, null);
    }

    private void modify(String punishmentId, Modification.Type type, Long duration) {
        executor.processModifiedPunishment(new SyncResponse.ModifiedPunishment(player.toString(), "Player",
                new SyncResponse.PunishmentWithModifications(punishmentId, Collections.singletonList(
                        new SyncResponse.PunishmentModification(type.name(), MODIFIED_AT, duration)))));
    }
}
