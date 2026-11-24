package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.core.imports.source.SourceException;
import lombok.Getter;

@Getter
final class ImportFailure extends RuntimeException {
    private static final String DATABASE_FAILED = "import.database_failed";

    private final ImportEnding ending;
    private final String messageKey;

    ImportFailure(ImportEnding ending, String messageKey, String detail) {
        super(detail);
        this.ending = ending;
        this.messageKey = messageKey;
    }

    ImportFailure(ImportEnding ending, String messageKey, Throwable cause) {
        this(ending, messageKey, cause.toString(), cause);
    }

    private ImportFailure(ImportEnding ending, String messageKey, String detail, Throwable cause) {
        super(detail, cause);
        this.ending = ending;
        this.messageKey = messageKey;
    }

    static ImportFailure cancelled() {
        return new ImportFailure(ImportEnding.CANCELLED, ImportEnding.CANCELLED.messageKey(), "Cancelled");
    }

    static ImportFailure fromSource(Exception failure) {
        if (failure instanceof SourceException) {
            SourceException refused = (SourceException) failure;
            String detail = String.valueOf(refused.getMessage());
            Throwable cause = refused.getCause();
            return cause != null ? new ImportFailure(ImportEnding.EXPORT_FAILED, refused.getMessageKey(), detail, cause)
                    : new ImportFailure(ImportEnding.EXPORT_FAILED, refused.getMessageKey(), detail);
        }
        return new ImportFailure(ImportEnding.EXPORT_FAILED, DATABASE_FAILED, failure);
    }
}
