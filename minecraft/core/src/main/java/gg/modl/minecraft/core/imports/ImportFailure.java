package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.core.imports.source.ImportProblem;
import gg.modl.minecraft.core.imports.source.SourceException;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

@Getter
final class ImportFailure extends RuntimeException {
    private final ImportEnding ending;
    private final @Nullable ImportProblem problem;

    ImportFailure(ImportEnding ending, ImportProblem problem, String detail) {
        this(ending, problem, detail, null);
    }

    ImportFailure(ImportEnding ending, ImportProblem problem, Throwable cause) {
        this(ending, problem, cause.toString(), cause);
    }

    private ImportFailure(ImportEnding ending, @Nullable ImportProblem problem, String detail, @Nullable Throwable cause) {
        super(detail, cause);
        this.ending = ending;
        this.problem = problem;
    }

    static ImportFailure cancelled() {
        return new ImportFailure(ImportEnding.CANCELLED, null, "Cancelled", null);
    }

    static ImportFailure ended(ImportEnding ending, Throwable cause) {
        return new ImportFailure(ending, null, cause.toString(), cause);
    }

    String getMessageKey() {
        ImportProblem known = problem;
        return known != null ? known.messageKey() : ending.messageKey();
    }

    ImportFailure afterLostConnection() {
        if (problem != ImportProblem.SOURCE_UNREADABLE) return this;
        return new ImportFailure(ending, ImportProblem.SOURCE_RECONNECT_FAILED, String.valueOf(getMessage()), getCause());
    }

    static ImportFailure fromSource(Exception failure) {
        if (failure instanceof SourceException) {
            SourceException refused = (SourceException) failure;
            return new ImportFailure(ImportEnding.EXPORT_FAILED, refused.getProblem(), String.valueOf(refused.getMessage()),
                    refused.getCause());
        }
        return new ImportFailure(ImportEnding.EXPORT_FAILED, ImportProblem.SOURCE_UNREADABLE, failure);
    }
}
