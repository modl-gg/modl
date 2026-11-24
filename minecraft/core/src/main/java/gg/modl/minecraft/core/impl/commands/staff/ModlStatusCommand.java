package gg.modl.minecraft.core.impl.commands.staff;

import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.boot.CoreAvailability;
import gg.modl.minecraft.core.command.AnyCoreState;
import gg.modl.minecraft.core.command.LocalConsoleOnly;
import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.plugin.PluginInfo;
import org.jspecify.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.command.CommandActor;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Command("modl") @LocalConsoleOnly @AnyCoreState
public class ModlStatusCommand {
    private final Platform platform;
    private final HttpClientHolder httpClientHolder;
    private final CoreAvailability availability;
    private final PanelUrl panelUrl;
    private final LocaleManager localeManager;

    public ModlStatusCommand(Platform platform, HttpClientHolder httpClientHolder, CoreAvailability availability,
                             PanelUrl panelUrl, LocaleManager localeManager) {
        this.platform = platform;
        this.httpClientHolder = httpClientHolder;
        this.availability = availability;
        this.panelUrl = panelUrl;
        this.localeManager = localeManager;
    }

    @Subcommand("status")
    @Description("Show a support report that is safe to share")
    public void status(CommandActor actor) {
        PluginStatusReport plugin = new PluginStatusReport(PluginInfo.VERSION, platform.getPlatformType(),
                platform.getServerVersion(), System.getProperty("java.version"), availability.state(),
                panelUrl.known().orElse("not known yet"));
        Optional<ModlHttpClient> client = httpClientHolder.connected();
        if (!client.isPresent()) {
            print(actor::reply, plugin.alone(), "support_status.backend_unavailable");
            return;
        }
        actor.reply(localeManager.getMessage("support_status.collecting"));
        client.get().getStatusReport()
                .thenAccept(report -> platform.runOnMainThread(() -> print(actor::reply, plugin.withBackend(report), null)))
                .exceptionally(failure -> {
                    platform.runOnMainThread(() -> print(actor::reply, plugin.alone(), "support_status.backend_failed"));
                    return null;
                });
    }

    private void print(Consumer<String> output, List<String> report, @Nullable String noteKey) {
        output.accept(localeManager.getMessage("support_status.header"));
        report.forEach(output);
        if (noteKey != null) output.accept(localeManager.getMessage(noteKey));
        output.accept(localeManager.getMessage("support_status.footer"));
    }
}
