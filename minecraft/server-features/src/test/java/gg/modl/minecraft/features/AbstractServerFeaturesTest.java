package gg.modl.minecraft.features;

import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.network.MemberActionSender;
import gg.modl.minecraft.core.feature.FeatureSettings;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.features.config.ServerFeaturesConfig;
import gg.modl.minecraft.features.config.StaffModeConfig;
import gg.modl.minecraft.features.link.CoreLink;
import gg.modl.minecraft.features.locale.FeaturesLocaleManager;
import gg.modl.minecraft.features.moderation.LocalModerationOps;
import gg.modl.minecraft.features.reporter.AutoReporter;
import gg.modl.minecraft.features.reporter.TicketCreator;
import gg.modl.minecraft.features.reporter.TicketRequest;
import gg.modl.minecraft.features.reporter.detection.DetectionSource;
import gg.modl.minecraft.features.reporter.hook.AntiCheatHook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractServerFeaturesTest {
    private static final String CHECK = "Speed";
    private static final PluginLogger LOGGER = PluginLogger.fromJul(Logger.getLogger("AbstractServerFeaturesTest"));

    @TempDir
    Path dataFolder;

    @Test
    void quittingClearsTheViolationsAndTheReportCooldownOfThePlayer() throws IOException {
        Files.write(dataFolder.resolve("server-features.yml"), String.join(System.lineSeparator(),
                "report-cooldown: 60",
                "report-violation-threshold:",
                "  default: 1"
        ).getBytes(StandardCharsets.UTF_8));
        RecordingTicketCreator tickets = new RecordingTicketCreator();
        TestFeatures features = new TestFeatures(new TestContext(dataFolder), ServerFeaturesConfig.load(dataFolder));
        features.enableStandalone(tickets, (action, onResult) -> { }, () -> "test", new LocaleManager());
        UUID player = UUID.randomUUID();

        features.flag(player);
        features.flag(player);
        assertEquals(1, tickets.requests.size());
        assertEquals(1, features.violationTracker.getRecords(player).size());

        features.playerLeft(player);
        assertTrue(features.violationTracker.getRecords(player).isEmpty());

        features.flag(player);
        assertEquals(2, tickets.requests.size());
        features.disable();
    }

    @Test
    void panelSettingsReachTheRecorderAndTheAnticheatHooksAndReportTheStatus() throws IOException {
        TestFeatures features = new TestFeatures(new TestContext(dataFolder), ServerFeaturesConfig.load(dataFolder));
        features.enableStandalone(new RecordingTicketCreator(), (action, onResult) -> { }, () -> "test", new LocaleManager());
        List<Boolean> reportedHooks = new ArrayList<>();
        features.reportStatusTo(() -> reportedHooks.add(features.featureStatus().isAnticheatIntegrationActive()));
        FeatureSettings enabled = new FeatureSettings(true, true, true, true, 90, 48, 128, "Grim");

        assertEquals(0, features.hookRegistrations);
        features.applyFeatureSettings(enabled);
        features.applyFeatureSettings(enabled);

        assertEquals(1, features.hookRegistrations);
        assertEquals(Collections.singletonList(enabled), features.appliedReplaySettings.subList(1, 2));
        assertEquals(Collections.singletonList(true), reportedHooks);
        assertEquals(Collections.singletonList("GrimAC"), features.featureStatus().getDetectedAnticheats());

        features.applyFeatureSettings(FeatureSettings.NONE);
        assertFalse(features.featureStatus().isAnticheatIntegrationActive());
        assertEquals(FeatureSettings.NONE, features.appliedReplaySettings.get(features.appliedReplaySettings.size() - 1));
        features.disable();
    }

    private static final class TestFeatures extends AbstractServerFeatures {
        private final List<FeatureSettings> appliedReplaySettings = new ArrayList<>();
        private int hookRegistrations;

        private TestFeatures(FeaturesPluginContext context, ServerFeaturesConfig config) {
            super(context, config, LOGGER);
        }

        private void flag(UUID player) {
            violationTracker.addViolation(player, DetectionSource.GRIM, CHECK, "verbose");
            AutoReporter reporter = startedAutoReporter();
            if (reporter == null) throw new AssertionError("the features are not running");
            reporter.checkAndReport(player, "Flagged", DetectionSource.GRIM, CHECK);
        }

        @Override
        protected void registerServerCommand(FeaturesLocaleManager localeManager) {
        }

        @Override
        protected LocalModerationOps startModerationHandlers(FeaturesLocaleManager localeManager,
                                                             StaffModeConfig staffModeConfig,
                                                             MemberActionSender actions, Supplier<String> serverName,
                                                             boolean coreOnAnotherServer) {
            return new NoModeration();
        }

        @Override
        protected void registerAntiCheatHooks(List<AntiCheatHook> hooks, AutoReporter autoReporter) {
            hookRegistrations++;
            hooks.add(new NamedHook());
        }

        @Override
        protected void applyReplaySettings(FeatureSettings previous, FeatureSettings next) {
            appliedReplaySettings.add(next);
        }

        @Override
        protected boolean supportsReplays() {
            return false;
        }

        @Override
        protected List<String> detectedAnticheats() {
            return Collections.singletonList("GrimAC");
        }

        @Override
        protected void registerPlatformEvents() {
        }

        @Override
        protected void registerProxyCommand(CoreLink coreLink, FeaturesLocaleManager localeManager) {
        }

        @Override
        protected void onDisable() {
        }
    }

    private static final class NamedHook implements AntiCheatHook {
        @Override
        public String getName() {
            return "GrimAC";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void register() {
        }

        @Override
        public void unregister() {
        }
    }

    private static final class RecordingTicketCreator implements TicketCreator {
        private final List<TicketRequest> requests = new ArrayList<>();

        @Override
        public void createTicket(TicketRequest request) {
            requests.add(request);
        }
    }

    private static final class TestContext implements FeaturesPluginContext {
        private final Path dataFolder;

        private TestContext(Path dataFolder) {
            this.dataFolder = dataFolder;
        }

        @Override
        public Path getDataFolder() {
            return dataFolder;
        }

        @Override
        public void saveDefaultResource(String resourcePath) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (input != null) Files.copy(input, dataFolder.resolve(resourcePath));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public FeatureScheduler getScheduler() {
            return new DirectScheduler();
        }

        @Override
        public FeaturePlayerProvider getPlayerProvider() {
            return new NoPlayers();
        }

        @Override
        public String getMinecraftVersion() {
            return "test";
        }
    }

    private static final class DirectScheduler implements FeatureScheduler {
        @Override
        public void runOnMainThread(Runnable task) {
            task.run();
        }

        @Override
        public void runForPlayer(UUID playerUuid, Runnable task) {
            task.run();
        }

        @Override
        public void runForPlayer(UUID playerUuid, Runnable task, Runnable gone) {
            task.run();
        }

        @Override
        public void runLater(Runnable task, long delayTicks) {
            task.run();
        }

        @Override
        public void runAsync(Runnable task) {
            task.run();
        }

        @Override
        public void runForPlayerLater(UUID playerUuid, Runnable task, long delayTicks) {
            task.run();
        }

        @Override
        public FeatureTask runTimerAsync(Runnable task, long delay, long period, TimeUnit unit) {
            return () -> { };
        }

        @Override
        public void cancelTask(FeatureTask task) {
            task.cancel();
        }
    }

    private static final class NoPlayers implements FeaturePlayerProvider {
        @Override
        public Collection<FeaturePlayer> getOnlinePlayers() {
            return Collections.emptyList();
        }

        @Override
        public boolean isOnline(UUID uuid) {
            return false;
        }

        @Override
        public void dispatchConsoleCommand(String command) {
        }
    }

    private static final class NoModeration implements LocalModerationOps {
        @Override
        public Set<UUID> moderatedPlayers() {
            return Collections.emptySet();
        }

        @Override
        public boolean isFrozen(UUID uuid) {
            return false;
        }

        @Override
        public void freeze(UUID target, UUID staff) {
        }

        @Override
        public void unfreeze(UUID target) {
        }

        @Override
        public boolean isInStaffMode(UUID uuid) {
            return false;
        }

        @Override
        public boolean hasUnreportedRefusal(UUID uuid) {
            return false;
        }

        @Override
        public void enterStaffMode(UUID uuid) {
        }

        @Override
        public void exitStaffMode(UUID uuid) {
        }

        @Override
        public boolean isVanished(UUID uuid) {
            return false;
        }

        @Override
        public void applyVanish(UUID uuid, boolean vanished) {
        }

        @Override
        public UUID targetOf(UUID staff) {
            return null;
        }

        @Override
        public void setTarget(UUID staff, UUID target) {
        }

        @Override
        public void clearTarget(UUID staff) {
        }
    }
}
