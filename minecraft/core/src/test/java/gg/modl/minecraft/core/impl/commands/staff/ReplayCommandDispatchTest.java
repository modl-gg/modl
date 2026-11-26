package gg.modl.minecraft.core.impl.commands.staff;

import gg.modl.minecraft.core.PluginLoader;
import gg.modl.minecraft.core.boot.CoreState;
import gg.modl.minecraft.core.imports.sql.LoadedDriverLibraries;
import gg.modl.minecraft.core.service.ChatMessageCache;
import gg.modl.minecraft.core.support.FakeCommandActor;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.util.Permissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayCommandDispatchTest {
    @TempDir
    Path dataDirectory;

    private final FakePlatform platform = new FakePlatform();
    private PluginLoader loader;

    @BeforeEach
    void loadCommands() {
        loader = PluginLoader.create(platform, dataDirectory, new ChatMessageCache(), () -> CoreState.RUNNING,
                new LoadedDriverLibraries(), false, false);
    }

    @AfterEach
    void unloadCommands() {
        loader.shutdown();
    }

    @ParameterizedTest
    @ValueSource(strings = {"replay record Steve", "replay stop Steve", "replay status Steve"})
    void staffWithoutModActionsAreDenied(String input) {
        UUID uuid = UUID.randomUUID();
        loader.getCache().cacheStaffPermissions(uuid, "Helper", "helper", "Helper",
                Collections.singletonList(Permissions.TICKET_VIEW_ALL));
        FakeCommandActor helper = new FakeCommandActor(uuid, "Helper");

        platform.lamp().dispatch(helper, input);

        assertEquals(Collections.singletonList(loader.getLocaleManager().getMessage("general.no_permission")), helper.messages());
    }

    @ParameterizedTest
    @ValueSource(strings = {"replay record Steve", "replay stop Steve", "replay status Steve"})
    void staffWithModActionsPassTheGate(String input) {
        UUID uuid = UUID.randomUUID();
        loader.getCache().cacheStaffPermissions(uuid, "Mod", "mod", "Moderator",
                Collections.singletonList(Permissions.MOD_ACTIONS));
        FakeCommandActor moderator = new FakeCommandActor(uuid, "Mod");

        platform.lamp().dispatch(moderator, input);

        assertEquals(Collections.singletonList(loader.getLocaleManager().getMessage("replay.not_available")), moderator.messages());
    }
}
