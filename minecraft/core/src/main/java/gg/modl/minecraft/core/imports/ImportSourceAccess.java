package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.core.imports.source.ImportSource;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourceReader;

final class ImportSourceAccess {
    private ImportSourceAccess() {
    }

    static SourceReader open(ImportSource source) {
        try {
            return source.open();
        } catch (SourceException refused) {
            throw ImportFailure.fromSource(refused);
        } catch (RuntimeException | LinkageError unavailable) {
            throw new ImportFailure(ImportEnding.EXPORT_FAILED, "import.database_failed", unavailable);
        }
    }
}
