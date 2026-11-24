package gg.modl.minecraft.core.config;

import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.impl.commands.staff.ModlReloadCommand;
import gg.modl.minecraft.core.staff.action.StaffRequirement;
import gg.modl.minecraft.core.staff.action.StaffRequirements;
import gg.modl.minecraft.core.support.MapLocaleManager;
import org.junit.jupiter.api.Test;
import revxrsal.commands.annotation.list.AnnotationList;
import revxrsal.commands.command.CommandActor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConfigurationReloadTest {
    private final MapLocaleManager locale = new MapLocaleManager()
            .put("general.reloading", "Reloading...")
            .put("general.reload_success", "Reload complete.")
            .put("general.reload_error", "Error reloading: {error}");
    private final Cache cache = mock(Cache.class);
    private final List<String> replies = new ArrayList<>();

    @Test
    void theCommandAndTheSettingsMenuNeedTheSameRequirement() throws Exception {
        AnnotationList command = AnnotationList.create(ModlReloadCommand.class.getMethod("reload", CommandActor.class));

        assertEquals(StaffRequirements.RELOAD_CONFIGURATION, StaffRequirement.fromAnnotations(command));
    }

    @Test
    void aReloadRefreshesTheLocaleThePunishmentTypesAndTheRuntimeConfiguration() {
        AtomicInteger runtimeReloads = new AtomicInteger();

        new ConfigurationReload(locale, cache, runtimeReloads::incrementAndGet).reload(replies::add);

        verify(cache).clearPunishmentTypes();
        assertEquals(1, runtimeReloads.get());
        assertEquals(List.of("Reloading...", "Reload complete."), replies);
    }

    @Test
    void aFailedReloadIsReportedWithItsReason() {
        new ConfigurationReload(locale, cache, () -> {
            throw new IllegalStateException("bad yaml");
        }).reload(replies::add);

        assertEquals(List.of("Reloading...", "Error reloading: bad yaml"), replies);
    }
}
