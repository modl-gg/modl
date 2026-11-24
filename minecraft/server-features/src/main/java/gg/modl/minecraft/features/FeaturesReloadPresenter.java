package gg.modl.minecraft.features;

import gg.modl.minecraft.features.locale.FeaturesLocaleManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public final class FeaturesReloadPresenter {
    private final FeaturesLocaleManager localeManager;
    private final Supplier<FeaturesReloadResult> reloadHook;

    public FeaturesReloadPresenter(FeaturesLocaleManager localeManager, Supplier<FeaturesReloadResult> reloadHook) {
        this.localeManager = localeManager;
        this.reloadHook = reloadHook;
    }

    public void reload(Consumer<String> output) {
        output.accept(localeManager.getMessage("command.modlserver.reloading"));
        for (String message : describe(reloadHook.get())) {
            output.accept(message);
        }
    }

    public String usage() {
        return localeManager.getMessage("command.modlserver.usage");
    }

    public String noPermission() {
        return localeManager.getMessage("command.modlserver.no_permission");
    }

    private List<String> describe(FeaturesReloadResult result) {
        List<String> messages = new ArrayList<>();
        messages.add(result.isSuccessful()
                ? localeManager.getMessage("command.modlserver.reloaded")
                : localeManager.getMessage("command.modlserver.reload_failed",
                        mapOf("files", result.describeFailures())));
        if (result.needsRestart()) {
            messages.add(localeManager.getMessage("command.modlserver.restart_required",
                    mapOf("keys", result.describeRestartRequired())));
        }
        return messages;
    }
}
