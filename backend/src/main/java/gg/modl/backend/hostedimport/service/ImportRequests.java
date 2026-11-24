package gg.modl.backend.hostedimport.service;

import gg.modl.backend.hostedimport.model.RetentionChoice;
import org.jspecify.annotations.Nullable;
import java.util.Set;

public final class ImportRequests {
    private ImportRequests() {
    }

    public record BeginUpload(long sizeBytes, @Nullable String fileName, @Nullable String expectedSha256) {
    }

    public record StartDownload(String url, @Nullable String expectedSha256) {
    }

    public record Start(String importId, Set<String> staffIds, boolean mergeArchivedOwner, RetentionChoice retention) {
        public Start {
            staffIds = Set.copyOf(staffIds);
        }
    }
}
