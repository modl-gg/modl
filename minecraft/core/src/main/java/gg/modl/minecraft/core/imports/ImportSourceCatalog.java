package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.core.imports.advancedban.AdvancedBanSource;
import gg.modl.minecraft.core.imports.banmanager.BanManagerSource;
import gg.modl.minecraft.core.imports.bansystem.BanSystemSource;
import gg.modl.minecraft.core.imports.libertybans.LibertyBansSource;
import gg.modl.minecraft.core.imports.litebans.LiteBansDatabaseProvider;
import gg.modl.minecraft.core.imports.litebans.LiteBansSource;
import gg.modl.minecraft.core.imports.source.ImportSource;
import gg.modl.minecraft.core.imports.source.SourceContext;
import gg.modl.minecraft.core.imports.vanilla.VanillaSource;

import java.util.Arrays;
import java.util.List;

public final class ImportSourceCatalog {
    private static final String LITEBANS = "LiteBans";

    private ImportSourceCatalog() {
    }

    public static ImportSources of(SourceContext context) {
        return new ImportSources(sources(context));
    }

    static List<ImportSource> sources(SourceContext context) {
        return Arrays.asList(
                new LiteBansSource(() -> context.getPluginEnabled().test(LITEBANS), LiteBansDatabaseProvider::new,
                        context.getClock()),
                new AdvancedBanSource(context),
                new LibertyBansSource(context),
                new BanManagerSource(context),
                new BanSystemSource(context),
                new VanillaSource(context));
    }
}
