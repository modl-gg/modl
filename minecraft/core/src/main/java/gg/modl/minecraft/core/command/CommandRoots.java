package gg.modl.minecraft.core.command;

import revxrsal.commands.annotation.Command;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public final class CommandRoots {
    private CommandRoots() {}

    public static String[] declaredBy(Class<?> commandClass) {
        Set<String> roots = new LinkedHashSet<>();
        addRoots(roots, commandClass.getAnnotation(Command.class));
        for (Method method : commandClass.getMethods()) addRoots(roots, method.getAnnotation(Command.class));
        return roots.toArray(new String[0]);
    }

    private static void addRoots(Set<String> roots, Command command) {
        if (command != null) roots.addAll(Arrays.asList(command.value()));
    }
}
