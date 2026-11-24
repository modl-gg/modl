package gg.modl.minecraft.core.command.error;

import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.util.PluginLogger;
import revxrsal.commands.Lamp;
import revxrsal.commands.command.CommandActor;
import revxrsal.commands.node.DispatcherSettings;

import java.util.function.Consumer;

public final class CommandErrorLocalization {
    private CommandErrorLocalization() {
    }

    public static <A extends CommandActor> void install(Lamp.Builder<A> builder, LocaleManager localeManager,
                                                        PluginLogger logger,
                                                        Consumer<CommandErrorCatalog> platformErrors) {
        CommandErrorCatalog catalog = new CommandErrorCatalog();
        StandardCommandErrors.addTo(catalog);
        platformErrors.accept(catalog);
        CommandErrorReplies replies = new CommandErrorReplies(localeManager);
        builder.exceptionHandler(new LocalizedExceptionHandler<>(catalog, replies, logger));
        builder.dispatcherSettings().failureHandler(new LocalizedFailureHandler<>(replies,
                DispatcherSettings.<A>builder().build().failureHandler()));
    }
}
