package gg.modl.minecraft.core.impl.commands.staff;

import gg.modl.minecraft.core.command.LocalConsoleOnly;
import gg.modl.minecraft.core.imports.Imports;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Single;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.annotation.Suggest;
import revxrsal.commands.command.CommandActor;

@RequiredArgsConstructor @Command("modl") @LocalConsoleOnly
public class ModlImportCommand {
    public static final String SOURCE_PARAMETER = "source";

    private final Imports imports;

    @Subcommand("import")
    @Description("Show how to import from another plugin")
    public void usage(CommandActor actor) {
        imports.usage();
    }

    @Subcommand("import <source>")
    @Description("Preview an import from another plugin")
    public void preview(CommandActor actor, @Single @Named(SOURCE_PARAMETER) String source) {
        imports.preview(source);
    }

    @Subcommand("import <source> confirm")
    @Description("Start the previewed import")
    public void confirm(CommandActor actor, @Named(SOURCE_PARAMETER) String source,
                        @Optional @Named("scoped") @Suggest({"widen", "skip"}) @Nullable String scopedChoice,
                        @Optional @Named("history") @Suggest({"keep", "standard"}) @Nullable String historyChoice) {
        imports.confirm(source, scopedChoice, historyChoice);
    }

    @Subcommand("import status")
    @Description("Show the import status")
    public void status(CommandActor actor) {
        imports.status();
    }

    @Subcommand("import cancel")
    @Description("Cancel the running import")
    public void cancel(CommandActor actor) {
        imports.cancel();
    }
}
