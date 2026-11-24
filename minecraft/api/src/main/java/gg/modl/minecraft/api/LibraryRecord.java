package gg.modl.minecraft.api;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LibraryRecord {
    String groupId;
    String artifactId;
    String version;
    String fileName;
    String checksum;
    List<LibraryRelocation> relocations;
    @Nullable String bundledDirectory;
    @Nullable String classifier;

    public static LibraryRecord of(String groupId, String artifactId, String version, String fileName,
                                   @NonNull String checksum) {
        return new LibraryRecord(groupId, artifactId, version, fileName, checksum,
                Collections.<LibraryRelocation>emptyList(), null, null);
    }

    public LibraryRecord relocatedBy(List<LibraryRelocation> packageRelocations) {
        return new LibraryRecord(groupId, artifactId, version, fileName, checksum,
                Collections.unmodifiableList(new ArrayList<>(packageRelocations)), bundledDirectory, classifier);
    }

    public LibraryRecord bundledIn(@NonNull String resourceDirectory) {
        return new LibraryRecord(groupId, artifactId, version, fileName, checksum, relocations, resourceDirectory,
                classifier);
    }

    public LibraryRecord classifiedAs(@NonNull String artifactClassifier) {
        return new LibraryRecord(groupId, artifactId, version, fileName, checksum, relocations, bundledDirectory,
                artifactClassifier);
    }

    public boolean isBundled() {
        return bundledDirectory != null;
    }
}
