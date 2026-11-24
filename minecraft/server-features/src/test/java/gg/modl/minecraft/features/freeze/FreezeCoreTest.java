package gg.modl.minecraft.features.freeze;

import gg.modl.minecraft.features.locale.FeaturesLocaleManager;
import gg.modl.minecraft.core.locale.LocaleManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreezeCoreTest {

    private final FeaturesLocaleManager localeManager = new FeaturesLocaleManager(new LocaleManager());
    private final RecordingFreezeOps ops = new RecordingFreezeOps();
    private final FreezeCore core = new FreezeCore(localeManager, ops);

    @Test
    void freezeMarksPlayerAndNotifies() {
        UUID target = UUID.randomUUID();
        UUID staff = UUID.randomUUID();

        core.freeze(target, staff);

        assertTrue(core.isFrozen(target));
        assertEquals(1, ops.sent.size());
        assertEquals(target, ops.sent.get(0).uuid);
        assertEquals(localeManager.getMessage("freeze.frozen"), ops.sent.get(0).message);
    }

    @Test
    void unfreezeClearsPlayerAndNotifies() {
        UUID target = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        core.freeze(target, staff);
        ops.sent.clear();

        core.unfreeze(target);

        assertFalse(core.isFrozen(target));
        assertEquals(1, ops.sent.size());
        assertEquals(localeManager.getMessage("freeze.unfrozen"), ops.sent.get(0).message);
    }

    @Test
    void releaseLocalClearsTheFreezeWithoutMessagingThePlayer() {
        UUID target = UUID.randomUUID();
        core.freeze(target, UUID.randomUUID());
        ops.sent.clear();

        core.releaseLocal(target);

        assertFalse(core.isFrozen(target));
        assertTrue(ops.sent.isEmpty());
    }

    @Test
    void releaseLocalOfAPlayerWhoIsNotFrozenDoesNothing() {
        core.releaseLocal(UUID.randomUUID());

        assertTrue(ops.hookEvents.isEmpty());
    }

    @Test
    void frozenPlayersListsEveryFrozenPlayer() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        core.freeze(first, UUID.randomUUID());
        core.freeze(second, UUID.randomUUID());
        core.unfreeze(first);

        assertEquals(Collections.singleton(second), core.frozenPlayers());
    }

    @Test
    void freezeInvokesOnFrozenHook() {
        UUID target = UUID.randomUUID();

        core.freeze(target, UUID.randomUUID());

        assertEquals(Arrays.asList("onFrozen:" + target), ops.hookEvents);
    }

    @Test
    void unfreezeInvokesOnUnfrozenHook() {
        UUID target = UUID.randomUUID();
        core.freeze(target, UUID.randomUUID());
        ops.hookEvents.clear();

        core.unfreeze(target);

        assertEquals(Arrays.asList("onUnfrozen:" + target), ops.hookEvents);
    }

    @Test
    void releaseLocalInvokesOnUnfrozenHook() {
        UUID target = UUID.randomUUID();
        core.freeze(target, UUID.randomUUID());
        ops.hookEvents.clear();

        core.releaseLocal(target);

        assertEquals(Arrays.asList("onUnfrozen:" + target), ops.hookEvents);
    }

    @Test
    void repeatedFreezeDoesNotReapplyHooksOrNotify() {
        UUID target = UUID.randomUUID();
        UUID staff = UUID.randomUUID();

        core.freeze(target, staff);
        core.freeze(target, UUID.randomUUID());

        assertTrue(core.isFrozen(target));
        assertEquals(1, ops.sent.size());
        assertEquals(Arrays.asList("onFrozen:" + target), ops.hookEvents);
    }

    @Test
    void unfreezingAPlayerWhoIsNotFrozenDoesNothing() {
        UUID target = UUID.randomUUID();

        core.unfreeze(target);

        assertFalse(core.isFrozen(target));
        assertTrue(ops.sent.isEmpty());
        assertTrue(ops.hookEvents.isEmpty());
    }

    @Test
    void refreezeAfterUnfreezeCapturesFreshAnchor() {
        UUID target = UUID.randomUUID();

        core.freeze(target, UUID.randomUUID());
        core.unfreeze(target);
        core.freeze(target, UUID.randomUUID());

        assertEquals(Arrays.asList(
                "onFrozen:" + target,
                "onUnfrozen:" + target,
                "onFrozen:" + target), ops.hookEvents);
        assertTrue(core.isFrozen(target));
    }

    private static final class RecordingFreezeOps implements FreezeOps {
        final List<Sent> sent = new ArrayList<>();
        final List<String> hookEvents = new ArrayList<>();

        @Override
        public void sendMessage(UUID uuid, String message) {
            sent.add(new Sent(uuid, message));
        }

        @Override
        public void onFrozen(UUID target) {
            hookEvents.add("onFrozen:" + target);
        }

        @Override
        public void onUnfrozen(UUID target) {
            hookEvents.add("onUnfrozen:" + target);
        }
    }

    private static final class Sent {
        final UUID uuid;
        final String message;

        Sent(UUID uuid, String message) {
            this.uuid = uuid;
            this.message = message;
        }
    }
}
