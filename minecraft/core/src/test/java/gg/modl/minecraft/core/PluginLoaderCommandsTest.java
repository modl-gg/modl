package gg.modl.minecraft.core;

import gg.modl.minecraft.core.imports.sql.LoadedDriverLibraries;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import gg.modl.minecraft.core.boot.CoreState;
import gg.modl.minecraft.core.boot.TestBackendSessions;
import gg.modl.minecraft.core.service.ChatMessageCache;
import gg.modl.minecraft.core.support.FakeCommandActor;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.TempConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import revxrsal.commands.Lamp;
import revxrsal.commands.brigadier.BrigadierConverter;
import revxrsal.commands.brigadier.BrigadierParser;
import revxrsal.commands.brigadier.types.ArgumentTypes;
import revxrsal.commands.command.CommandActor;
import revxrsal.commands.command.ExecutableCommand;
import revxrsal.commands.node.ParameterNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginLoaderCommandsTest {
    private static final List<String> CORE_ROOTS = Arrays.asList("ban", "mute", "kick", "pardon", "unban", "unmute",
            "history", "report", "modl", "staffchat");

    @TempDir
    Path dataDirectory;

    private final AtomicReference<CoreState> state = new AtomicReference<>(CoreState.STARTING);
    private final FakeCommandActor console = new FakeCommandActor(null, "CONSOLE");

    @Test
    void everyRegisteredCommandMergesIntoOneBrigadierTree() {
        withLoadedCommands(lamp -> {
            BrigadierTree tree = new BrigadierTree();

            assertDoesNotThrow(() -> tree.addAll(lamp));
            assertTrue(tree.rootNames().containsAll(CORE_ROOTS), "missing roots in " + tree.rootNames());
        });
    }

    @Test
    void configuredAliasesRouteEachPardonRootToItsOwnHandler() throws IOException {
        TempConfigs.write(dataDirectory.resolve("config.yml"),
                "commands:",
                "  unban: \"ub\"",
                "  unmute: \"\"");

        withLoadedCommands(lamp -> assertEquals(
                mapOf("pardon", singletonList("pardon"), "ub", singletonList("unban")),
                handlersByRoot(lamp, Arrays.asList("pardon", "ub", "unban", "unmute"))));
    }

    @Test
    void aCommandBeforeTheCoreIsReadyRepliesWithTheConnectingMessageWithoutReachingTheServer() {
        state.set(CoreState.STARTING);

        withLoadedCommands(lamp -> assertTrue(replyTo(lamp, "ban Steve").contains("Connection to panel failed"),
                console.messages().toString()));
    }

    @Test
    void aCommandAfterTheCoreFailedRepliesWithTheNotRunningMessage() {
        state.set(CoreState.UNAVAILABLE);

        withLoadedCommands(lamp -> assertTrue(replyTo(lamp, "ban Steve").contains("is not running"),
                console.messages().toString()));
    }

    @Test
    void theSameRegisteredCommandRunsOnceTheCoreIsReady() {
        withLoadedCommands(lamp -> {
            assertTrue(replyTo(lamp, "modl").contains("Connection to panel failed"));
            console.messages().clear();

            state.set(CoreState.RUNNING);
            lamp.dispatch(console, "modl");

            assertFalse(console.messages().isEmpty());
            assertFalse(console.messages().stream().anyMatch(line ->
                    line.contains("Connection to panel failed") || line.contains("is not running")),
                    console.messages().toString());
        });
    }

    @Test
    void connectingResolvesThePanelUrlAndTheHttpClientTheCommandsAlreadyHold() {
        withLoader(loader -> {
            loader.connect(TestBackendSessions.unreachable(), services -> { });

            assertEquals("https://panel.example.test", loader.getPanelUrl().get());
            assertDoesNotThrow(loader::getHttpClient);
        });
    }

    @Test
    void aFailedConnectionUnwindsItsServicesButKeepsTheCommandsRegistered() {
        withLoader(loader -> {
            int commands = loader.getLamp().registry().commands().size();
            IllegalStateException wiringFailure = new IllegalStateException("listener registration failed");

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> loader.connect(TestBackendSessions.unreachable(), services -> {
                        throw wiringFailure;
                    }));

            assertSame(wiringFailure, thrown);
            assertEquals(commands, loader.getLamp().registry().commands().size());
        });
    }

    @Test
    void commandRedactionFollowsAliasesAndTheConfigurationReload() throws IOException {
        Path config = dataDirectory.resolve("config.yml");
        TempConfigs.write(config, "commands:", "  modl: \"modl|panel\"", "redacted_commands:", "  - \"pin\"");
        FakePlatform platform = new FakePlatform();
        state.set(CoreState.RUNNING);

        withLoader(platform, loader -> {
            assertEquals("/panel link [REDACTED]", capturedCommand(loader, "/panel link ABCD-1234"));
            assertEquals("/pin [REDACTED]", capturedCommand(loader, "/pin 1234"));

            assertDoesNotThrow(() -> TempConfigs.write(config,
                    "commands:", "  modl: \"modl|panel\"", "redacted_commands:", "  - \"vault\""));
            platform.lamp().dispatch(console, "modl reload");

            assertEquals("/vault [REDACTED]", capturedCommand(loader, "/vault 9876"));
            assertEquals("/panel link [REDACTED]", capturedCommand(loader, "/panel link ABCD-1234"));
        });
    }

    private static String capturedCommand(PluginLoader loader, String command) {
        loader.getChatCommandLogService().addCommand("player-uuid", "Player", command, "lobby");
        return loader.getChatCommandLogService().drainCommandBuffer().get(0).getCommand();
    }

    private String replyTo(Lamp<CommandActor> lamp, String input) {
        lamp.dispatch(console, input);
        return String.join("\n", console.messages());
    }

    private void withLoadedCommands(Consumer<Lamp<CommandActor>> assertions) {
        FakePlatform platform = new FakePlatform();
        withLoader(platform, loader -> assertions.accept(platform.lamp()));
    }

    private void withLoader(Consumer<PluginLoader> assertions) {
        withLoader(new FakePlatform(), assertions);
    }

    private void withLoader(FakePlatform platform, Consumer<PluginLoader> assertions) {
        PluginLoader loader = PluginLoader.create(platform, dataDirectory, new ChatMessageCache(), state::get, new LoadedDriverLibraries(),
                false, false);
        try {
            assertions.accept(loader);
        } finally {
            loader.shutdown();
        }
    }

    private static Map<String, List<String>> handlersByRoot(Lamp<CommandActor> lamp, Collection<String> roots) {
        Map<String, List<String>> handlers = new TreeMap<>();
        for (ExecutableCommand<CommandActor> command : lamp.registry()) {
            String root = command.firstNode().name();
            if (!roots.contains(root)) continue;
            handlers.computeIfAbsent(root, ignored -> new ArrayList<>()).add(command.function().method().getName());
        }
        return handlers;
    }

    private static final class BrigadierTree implements BrigadierConverter<CommandActor, Object> {
        private final ArgumentTypes<CommandActor> argumentTypes = ArgumentTypes.<CommandActor>builder().build();
        private final RootCommandNode<Object> root = new RootCommandNode<>();

        void addAll(Lamp<CommandActor> lamp) {
            BrigadierParser<Object, CommandActor> parser = new BrigadierParser<>(this);
            for (ExecutableCommand<CommandActor> command : lamp.registry()) {
                BrigadierParser.addChild(root, parser.createNode(command));
            }
        }

        Set<String> rootNames() {
            Set<String> names = new HashSet<>();
            for (CommandNode<Object> child : root.getChildren()) names.add(child.getName());
            return names;
        }

        @Override
        public ArgumentType<?> getArgumentType(ParameterNode<CommandActor, ?> parameter) {
            return argumentTypes.type(parameter);
        }

        @Override
        public CommandActor createActor(Object sender, Lamp<CommandActor> lamp) {
            throw new UnsupportedOperationException("building the command tree never evaluates requirements");
        }
    }
}
