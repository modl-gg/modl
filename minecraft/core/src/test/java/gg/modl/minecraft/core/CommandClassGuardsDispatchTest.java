package gg.modl.minecraft.core;

import gg.modl.minecraft.core.imports.sql.LoadedDriverLibraries;
import gg.modl.minecraft.core.boot.CoreState;
import gg.modl.minecraft.core.command.AdminOnly;
import gg.modl.minecraft.core.command.LocalConsoleOnly;
import gg.modl.minecraft.core.command.PlayerOnly;
import gg.modl.minecraft.core.command.RequiresPermission;
import gg.modl.minecraft.core.command.RequiresPlayerView;
import gg.modl.minecraft.core.command.Staff2faGated;
import gg.modl.minecraft.core.command.StaffNo2fa;
import gg.modl.minecraft.core.command.StaffOnly;
import gg.modl.minecraft.core.service.ChatMessageCache;
import gg.modl.minecraft.core.staff.action.StaffRequirement;
import gg.modl.minecraft.core.support.FakeCommandActor;
import gg.modl.minecraft.core.support.FakePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import revxrsal.commands.Lamp;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.command.CommandActor;
import revxrsal.commands.command.ExecutableCommand;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CommandClassGuardsDispatchTest {
    private static final List<Class<? extends Annotation>> GUARDS = Arrays.asList(AdminOnly.class, PlayerOnly.class,
            RequiresPermission.class, RequiresPlayerView.class, Staff2faGated.class, StaffNo2fa.class, StaffOnly.class, LocalConsoleOnly.class);

    @TempDir
    Path dataDirectory;

    private final FakePlatform platform = new FakePlatform();
    private PluginLoader loader;

    @BeforeEach
    void loadCommands() {
        loader = PluginLoader.create(platform, dataDirectory, new ChatMessageCache(), () -> CoreState.RUNNING, new LoadedDriverLibraries(), false, false);
    }

    @AfterEach
    void unloadCommands() {
        loader.shutdown();
    }

    @ParameterizedTest
    @ValueSource(strings = {"staffchat", "staffchat hello staff", "chat staff", "chat", "localchat",
            "localchat hello everyone"})
    void aPlayerWhoIsNotStaffIsDeniedByRealDispatch(String input) {
        FakeCommandActor player = new FakeCommandActor(UUID.randomUUID(), "Player");

        platform.lamp().dispatch(player, input);

        assertEquals(Collections.singletonList(noPermission()), player.messages());
        assertEquals(Collections.emptyList(), platform.staffBroadcasts());
        assertEquals(Collections.emptyList(), platform.broadcasts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"staffchat", "chat staff", "localchat"})
    void aStaffMemberIsNotDeniedForLackOfStaffStatus(String input) {
        UUID uuid = UUID.randomUUID();
        loader.getCache().cacheStaffPermissions(uuid, "Mod", "mod", "Moderator", Collections.singletonList("staff.modactions"));
        FakeCommandActor staff = new FakeCommandActor(uuid, "Mod");

        platform.lamp().dispatch(staff, input);

        assertFalse(staff.messages().isEmpty(), input + " answered nothing");
        assertFalse(staff.messages().contains(noPermission()), staff.messages().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"inspect Target", "inspect Target -p", "inspect #BANAAAAA", "history Target", "history Target -p",
            "notes Target", "notes Target -p", "alts Target", "alts Target -p"})
    void staffWithoutViewingAreDeniedEveryPlayerRecordCommand(String input) {
        UUID uuid = UUID.randomUUID();
        loader.getCache().cacheStaffPermissions(uuid, "Helper", "helper", "Helper",
                Arrays.asList("player.note", "player.warn", "ticket.view.all"));
        FakeCommandActor helper = new FakeCommandActor(uuid, "Helper");

        platform.lamp().dispatch(helper, input);

        assertEquals(Collections.singletonList(noPermission()), helper.messages());
    }

    @Test
    void everyClassLevelGuardReachesEveryHandlerOfItsClass() {
        List<String> unguarded = new ArrayList<>();
        for (ExecutableCommand<CommandActor> command : platform.lamp().registry()) {
            Method handler = command.function().method();
            for (Class<? extends Annotation> guard : GUARDS) {
                if (handler.getDeclaringClass().isAnnotationPresent(guard) && !command.annotations().contains(guard)) {
                    unguarded.add(handler.getDeclaringClass().getSimpleName() + "." + handler.getName() + " lacks "
                            + guard.getSimpleName());
                }
            }
        }

        assertEquals(Collections.emptyList(), unguarded);
    }

    @Test
    void aMethodLevelGuardWinsOverTheClassLevelOne() {
        Lamp<CommandActor> lamp = Lamp.<CommandActor>builder().build();
        lamp.register(new OverridingCommand());
        for (ExecutableCommand<CommandActor> command : lamp.registry()) {
            StaffRequirement requirement = StaffRequirement.fromAnnotations(command.annotations());
            boolean overrides = "overriding".equals(command.function().method().getName());

            assertEquals(overrides ? "guard.method" : "guard.class", requirement.getPermission());
            assertEquals(!overrides, requirement.isStaff2fa(), "staff 2FA of " + command.function().method().getName());
            assertEquals(true, requirement.isStaffOnly());
        }
    }

    private String noPermission() {
        return loader.getLocaleManager().getMessage("general.no_permission");
    }

    @Command("guardprobe")
    @StaffOnly
    @RequiresPermission("guard.class")
    public static final class OverridingCommand {
        @Subcommand("inherited")
        public void inherited(CommandActor actor) {
            actor.reply("inherited");
        }

        @Subcommand("overriding")
        @StaffNo2fa
        @RequiresPermission("guard.method")
        public void overriding(CommandActor actor) {
            actor.reply("overriding");
        }
    }
}
