package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.util.CommandUtil;
import revxrsal.commands.Lamp;
import revxrsal.commands.command.CommandActor;

import java.util.UUID;

public final class StaffActors {
    private StaffActors() {
    }

    public static StaffActor of(CommandActor actor) {
        return new LampStaffActor(actor);
    }

    public static StaffActor player(UUID uuid, Platform platform) {
        return new LampStaffActor(new PlatformPlayerActor(uuid, platform));
    }

    private static final class LampStaffActor implements StaffActor {
        private final CommandActor actor;

        private LampStaffActor(CommandActor actor) {
            this.actor = actor;
        }

        @Override
        public UUID uuid() {
            return actor.uniqueId();
        }

        @Override
        public boolean isConsole() {
            return CommandUtil.isConsole(actor);
        }

        @Override
        public void reply(String message) {
            actor.reply(message);
        }

        @Override
        public CommandActor commandActor() {
            return actor;
        }
    }

    private static final class PlatformPlayerActor implements CommandActor {
        private final UUID uuid;
        private final Platform platform;

        private PlatformPlayerActor(UUID uuid, Platform platform) {
            this.uuid = uuid;
            this.platform = platform;
        }

        @Override
        public String name() {
            AbstractPlayer player = platform.getPlayer(uuid);
            return player != null ? player.getUsername() : uuid.toString();
        }

        @Override
        public UUID uniqueId() {
            return uuid;
        }

        @Override
        public void reply(String message) {
            platform.sendMessage(uuid, message);
        }

        @Override
        public void error(String message) {
            platform.sendMessage(uuid, message);
        }

        @Override
        public void sendRawMessage(String message) {
            platform.sendMessage(uuid, message);
        }

        @Override
        public void sendRawError(String message) {
            platform.sendMessage(uuid, message);
        }

        @Override
        public Lamp<?> lamp() {
            throw new UnsupportedOperationException("a network staff action has no command dispatcher");
        }
    }
}
