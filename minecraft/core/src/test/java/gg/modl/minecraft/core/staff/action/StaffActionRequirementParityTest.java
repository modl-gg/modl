package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.core.imports.sql.LoadedDriverLibraries;
import gg.modl.minecraft.core.PluginLoader;
import gg.modl.minecraft.core.boot.CoreState;
import gg.modl.minecraft.core.impl.commands.player.HackReportCommand;
import gg.modl.minecraft.core.impl.commands.staff.AltsCommand;
import gg.modl.minecraft.core.impl.commands.staff.FreezeCommand;
import gg.modl.minecraft.core.impl.commands.staff.HistoryCommand;
import gg.modl.minecraft.core.impl.commands.staff.InspectCommand;
import gg.modl.minecraft.core.impl.commands.staff.NotesCommand;
import gg.modl.minecraft.core.impl.commands.staff.StaffCommand;
import gg.modl.minecraft.core.impl.commands.staff.StaffModeCommand;
import gg.modl.minecraft.core.impl.commands.staff.TargetCommand;
import gg.modl.minecraft.core.impl.commands.staff.VanishCommand;
import gg.modl.minecraft.core.service.ChatMessageCache;
import gg.modl.minecraft.core.support.FakePlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import revxrsal.commands.command.CommandActor;
import revxrsal.commands.command.ExecutableCommand;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffActionRequirementParityTest {
    @TempDir
    Path dataDirectory;

    @Test
    void everyAdaptedCommandIsGuardedExactlyLikeItsTypedService() {
        Map<Class<?>, StaffRequirement> expected = new LinkedHashMap<>();
        expected.put(FreezeCommand.class, FreezeActions.REQUIREMENT);
        expected.put(VanishCommand.class, VanishActions.REQUIREMENT);
        expected.put(StaffModeCommand.class, StaffModeActions.REQUIREMENT);
        expected.put(TargetCommand.class, TargetActions.REQUIREMENT);
        expected.put(StaffCommand.class, StaffMenuActions.REQUIREMENT);
        expected.put(InspectCommand.class, InspectActions.REQUIREMENT);
        expected.put(HistoryCommand.class, StaffRequirements.VIEW_PLAYERS);
        expected.put(NotesCommand.class, StaffRequirements.VIEW_PLAYERS);
        expected.put(AltsCommand.class, StaffRequirements.VIEW_PLAYERS);
        expected.put(HackReportCommand.class, HackReportActions.REQUIREMENT);

        FakePlatform platform = new FakePlatform();
        PluginLoader loader = PluginLoader.create(platform, dataDirectory, new ChatMessageCache(),
                () -> CoreState.RUNNING, new LoadedDriverLibraries(), false, false);
        Map<Class<?>, StaffRequirement> actual = new HashMap<>();
        try {
            for (ExecutableCommand<CommandActor> command : platform.lamp().registry()) {
                Class<?> owner = command.function().method().getDeclaringClass();
                if (expected.containsKey(owner)) {
                    actual.put(owner, StaffRequirement.fromAnnotations(command.annotations()));
                }
            }
        } finally {
            loader.shutdown();
        }

        for (Map.Entry<Class<?>, StaffRequirement> entry : expected.entrySet()) {
            assertTrue(actual.containsKey(entry.getKey()), entry.getKey().getSimpleName() + " is not registered");
            assertEquals(entry.getValue(), actual.get(entry.getKey()), entry.getKey().getSimpleName());
        }
    }
}
