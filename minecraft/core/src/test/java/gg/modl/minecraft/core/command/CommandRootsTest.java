package gg.modl.minecraft.core.command;

import gg.modl.minecraft.core.impl.commands.staff.HistoryCommand;
import gg.modl.minecraft.core.impl.commands.staff.punishments.BanCommand;
import gg.modl.minecraft.core.impl.commands.staff.punishments.UnbanCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class CommandRootsTest {
    @Test
    void readsTheClassLevelRoot() {
        assertArrayEquals(new String[]{"ban"}, CommandRoots.declaredBy(BanCommand.class));
    }

    @Test
    void readsMethodLevelRoots() {
        assertArrayEquals(new String[]{"history"}, CommandRoots.declaredBy(HistoryCommand.class));
    }

    @Test
    void readsTheRootDeclaredOnASubclass() {
        assertArrayEquals(new String[]{"unban"}, CommandRoots.declaredBy(UnbanCommand.class));
    }
}
