package gg.modl.minecraft.core.service;

import gg.modl.minecraft.core.chat.CommandMatcher;

import java.util.Objects;

public final class ChatCommandLogPolicy {
    private final boolean chatLogged;
    private final boolean commandsLogged;
    private final CommandMatcher unloggedCommands;

    public ChatCommandLogPolicy(boolean chatLogged, boolean commandsLogged, CommandMatcher unloggedCommands) {
        this.chatLogged = chatLogged;
        this.commandsLogged = commandsLogged;
        this.unloggedCommands = Objects.requireNonNull(unloggedCommands, "unloggedCommands");
    }

    public static ChatCommandLogPolicy everything() {
        return new ChatCommandLogPolicy(true, true, CommandMatcher.none());
    }

    public boolean logsChat() {
        return chatLogged;
    }

    public boolean logsCommand(String commandLine) {
        return commandsLogged && !unloggedCommands.matches(commandLine);
    }
}
