package gg.modl.minecraft.core;

import gg.modl.minecraft.core.imports.sql.LoadedDriverLibraries;
import gg.modl.minecraft.core.boot.CoreState;
import gg.modl.minecraft.core.network.ActionOrigin;
import gg.modl.minecraft.core.network.MemberAction;
import gg.modl.minecraft.core.network.MemberActionOutcome;
import gg.modl.minecraft.core.service.ChatMessageCache;
import gg.modl.minecraft.core.support.FakeCommandActor;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.minecraft.core.support.TempConfigs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import revxrsal.commands.Lamp;
import revxrsal.commands.command.CommandActor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalConsoleCommandsTest {
    private static final List<String> IMPORT_COMMANDS = Arrays.asList("modl import", "modl import litebans",
            "modl import litebans confirm skip keep", "modl import status", "modl import cancel",
            "mp import litebans");
    private static final long WAIT_SECONDS = 10;

    @TempDir
    Path dataDirectory;

    private final FakePlatform platform = new FakePlatform();
    private PluginLoader loader;
    private String refusal;

    @BeforeEach
    void loadCommands() throws IOException {
        TempConfigs.write(dataDirectory.resolve("config.yml"), "commands:", "  modl: \"modl|mp\"");
        loader = PluginLoader.create(platform, dataDirectory, new ChatMessageCache(), () -> CoreState.RUNNING, new LoadedDriverLibraries(),
                false, false);
        refusal = loader.getLocaleManager().getMessage("general.local_console_only");
    }

    @AfterEach
    void stop() {
        loader.shutdown();
    }

    @Test
    void theLocalConsoleRunsTheImportCommandUnderEveryAlias() {
        for (String command : Arrays.asList("modl import", "mp import")) {
            FakeCommandActor console = new FakeCommandActor(null, "CONSOLE");
            platform.addConsoleSender(console);

            lamp().dispatch(console, command);

            assertFalse(repliesOf(console).contains(refusal), command);
        }
        assertTrue(consoleLines().contains(loader.getLocaleManager().getMessage("import.usage")),
                consoleLines().toString());
    }

    @Test
    void aPlayerIsRefused() {
        assertRefused(() -> new FakeCommandActor(UUID.randomUUID(), "Player"));
    }

    @Test
    void aPlayerPresentingTheConsoleUuidIsRefused() {
        assertRefused(() -> new FakeCommandActor(new UUID(0, 0), "Player"));
    }

    @Test
    void aRemoteConsoleOrCommandBlockIsRefused() {
        assertRefused(() -> new FakeCommandActor(null, "Rcon"));
    }

    @Test
    void theNetworkConsoleIsRefusedUnderEveryAlias() {
        for (String command : IMPORT_COMMANDS) {
            platform.console().messages().clear();

            loader.dispatchModlConsoleCommand(command);

            assertTrue(repliesOf(platform.console()).contains(refusal), command);
        }
        assertTrue(consoleLines().stream().noneMatch(this::isImportLine), consoleLines().toString());
    }

    @Test
    void anotherCaseCannotBypassTheRefusal() {
        for (String command : Arrays.asList("MODL IMPORT LITEBANS", "Mp Import", "modl  import  litebans")) {
            loader.dispatchModlConsoleCommand(command);
        }

        assertTrue(consoleLines().stream().noneMatch(this::isImportLine), consoleLines().toString());
    }

    @Test
    void aGrantedProxyCommandFromAMemberCannotReachTheImport() throws Exception {
        CompletableFuture<MemberActionOutcome> outcome = new CompletableFuture<>();
        MemberAction proxyCommand = MemberAction.proxyCommand("/modl import litebans")
                .stampedBy("survival", "member-1", true);

        loader.getMemberActionExecutor().submit(proxyCommand, ActionOrigin.NETWORK, outcome::complete);

        assertEquals(MemberActionOutcome.ACCEPTED, outcome.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(repliesOf(platform.console()).contains(refusal));
        assertTrue(consoleLines().stream().noneMatch(this::isImportLine), consoleLines().toString());
    }

    @Test
    void onlyTheLocalConsoleSeesTheImportInTheHelp() {
        String usage = loader.getLocaleManager().getMessage("help.staff_commands.modl_import.usage");
        FakeCommandActor console = new FakeCommandActor(new UUID(0, 0), "CONSOLE");
        platform.addConsoleSender(console);
        FakeCommandActor player = new FakeCommandActor(UUID.randomUUID(), "Player");

        for (int page = 1; page <= 5; page++) {
            lamp().dispatch(console, "modl help " + page);
            lamp().dispatch(player, "modl help " + page);
        }

        assertTrue(repliesOf(console).contains(usage + " "), repliesOf(console));
        assertTrue(repliesOf(console).contains("/staffmenu") || repliesOf(console).contains("/inspect"), repliesOf(console));
        assertFalse(repliesOf(player).contains(usage + " "), repliesOf(player));
    }

    private void assertRefused(ActorFactory actors) {
        for (String command : IMPORT_COMMANDS) {
            FakeCommandActor actor = actors.create();

            lamp().dispatch(actor, command);

            assertTrue(repliesOf(actor).contains(refusal), command);
        }
        assertTrue(consoleLines().stream().noneMatch(this::isImportLine), consoleLines().toString());
    }

    private boolean isImportLine(String line) {
        return line.contains("LiteBans") || line.contains("/modl import");
    }

    private List<String> consoleLines() {
        return ((RecordingPluginLogger) platform.getLogger()).infos();
    }

    private Lamp<CommandActor> lamp() {
        return platform.lamp();
    }

    private static String repliesOf(FakeCommandActor actor) {
        return String.join("\n", actor.messages()) + "\n" + String.join("\n", actor.errors());
    }

    private interface ActorFactory {
        FakeCommandActor create();
    }
}
