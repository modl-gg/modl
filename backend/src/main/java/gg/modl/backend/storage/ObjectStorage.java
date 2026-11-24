package gg.modl.backend.storage;

import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public interface ObjectStorage {

    UploadTarget presignUpload(String key, String contentType, long contentLength);

    String publicUrl(String key);

    Optional<String> keyFromPublicUrl(String url);

    String publicBaseUrl();

    boolean exists(String key);

    Optional<StoredObject> stat(String key);

    InputStream read(String key);

    void write(String key, InputStream data, long contentLength, String contentType);

    Set<String> deleteAll(Collection<String> keys);

    default boolean delete(String key) {
        return deleteAll(List.of(key)).isEmpty();
    }

    Stream<ListedObject> list(String prefix);

    boolean hasCapacityFor(long bytes);

    Optional<String> readOwnershipMarker(String namespace);

    void writeOwnershipMarker(String namespace, String owner);
}
