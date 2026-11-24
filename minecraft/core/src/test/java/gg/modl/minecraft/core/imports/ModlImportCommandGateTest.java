package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.core.command.LocalConsoleOnly;
import gg.modl.minecraft.core.impl.commands.staff.ModlImportCommand;
import org.junit.jupiter.api.Test;
import revxrsal.commands.Lamp;
import revxrsal.commands.command.CommandActor;
import revxrsal.commands.command.ExecutableCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModlImportCommandGateTest {
    private static final int SUBCOMMANDS = 5;

    @Test
    void everySubcommandCarriesTheConsoleOnlyRequirementAtDispatch() {
        Lamp<CommandActor> lamp = Lamp.<CommandActor>builder().build();
        lamp.register(new ModlImportCommand(null));

        int guarded = 0;
        for (ExecutableCommand<CommandActor> command : lamp.registry()) {
            assertTrue(command.annotations().contains(LocalConsoleOnly.class), command.function().name());
            guarded++;
        }
        assertEquals(SUBCOMMANDS, guarded);
    }
}
