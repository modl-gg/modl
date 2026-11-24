package gg.modl.backend.storage.backend;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gg.modl.backend.storage.ListedObject;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StorageLocation;
import gg.modl.backend.storage.StorageType;
import gg.modl.backend.storage.StoredObject;
import gg.modl.backend.storage.UploadTarget;
import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

public class SwitchableObjectStorage implements ObjectStorage, StorageLocation {
    private static final long LOCATED_KEYS = 10_000;

    private final AtomicReference<StorageRoute> route;
    private final AtomicReference<List<Function<String, Optional<String>>>> formerUrlParsers =
        new AtomicReference<>(List.of());
    private final Cache<String, ObjectStorage> locatedHolders = Caffeine.newBuilder().maximumSize(LOCATED_KEYS).build();

    SwitchableObjectStorage(StorageRoute initial) {
        this.route = new AtomicReference<>(initial);
    }

    StorageRoute route() {
        return route.get();
    }

    void use(StorageRoute next) {
        route.set(next);
        locatedHolders.invalidateAll();
    }

    void recognizeFormerUrls(List<Function<String, Optional<String>>> parsers) {
        formerUrlParsers.set(List.copyOf(parsers));
    }

    @Override
    public boolean writesToLocalDisk() {
        return route.get().writeKind() == StorageType.LOCAL;
    }

    @Override
    public UploadTarget presignUpload(String key, String contentType, long contentLength) {
        return route.get().writeTarget().presignUpload(key, contentType, contentLength);
    }

    @Override
    public String publicUrl(String key) {
        return holderOf(key).publicUrl(key);
    }

    @Override
    public Optional<String> keyFromPublicUrl(String url) {
        for (ObjectStorage storage : route.get().readOrder()) {
            Optional<String> key = storage.keyFromPublicUrl(url);
            if (key.isPresent()) {
                return key;
            }
        }
        return formerUrlParsers.get().stream().map(parser -> parser.apply(url)).flatMap(Optional::stream).findFirst();
    }

    @Override
    public String publicBaseUrl() {
        return route.get().writeTarget().publicBaseUrl();
    }

    @Override
    public boolean exists(String key) {
        return route.get().readOrder().stream().anyMatch(storage -> storage.exists(key));
    }

    @Override
    public Optional<StoredObject> stat(String key) {
        for (ObjectStorage storage : route.get().readOrder()) {
            Optional<StoredObject> stored = storage.stat(key);
            if (stored.isPresent()) {
                return stored;
            }
        }
        return Optional.empty();
    }

    @Override
    public InputStream read(String key) {
        return holderOf(key).read(key);
    }

    @Override
    public void write(String key, InputStream data, long contentLength, String contentType) {
        route.get().writeTarget().write(key, data, contentLength, contentType);
    }

    @Override
    public Set<String> deleteAll(Collection<String> keys) {
        Set<String> failed = new LinkedHashSet<>();
        for (ObjectStorage storage : route.get().deletionOrder()) {
            failed.addAll(storage.deleteAll(keys));
        }
        keys.forEach(locatedHolders::invalidate);
        return failed;
    }

    @Override
    public Stream<ListedObject> list(String prefix) {
        Stream<ListedObject> listed = Stream.empty();
        for (ObjectStorage storage : route.get().readOrder()) {
            Stream<ListedObject> stored = storage.list(prefix);
            listed = Stream.concat(listed, stored).onClose(stored::close);
        }
        return listed;
    }

    @Override
    public boolean hasCapacityFor(long bytes) {
        return route.get().writeTarget().hasCapacityFor(bytes);
    }

    @Override
    public Optional<String> readOwnershipMarker(String namespace) {
        return route.get().writeTarget().readOwnershipMarker(namespace);
    }

    @Override
    public void writeOwnershipMarker(String namespace, String owner) {
        route.get().writeTarget().writeOwnershipMarker(namespace, owner);
    }

    private ObjectStorage holderOf(String key) {
        List<ObjectStorage> readOrder = route.get().readOrder();
        ObjectStorage last = readOrder.getLast();
        if (readOrder.size() == 1) {
            return last;
        }
        ObjectStorage located = locatedHolders.getIfPresent(key);
        if (located != null && readOrder.contains(located)) {
            return located;
        }
        for (ObjectStorage storage : readOrder.subList(0, readOrder.size() - 1)) {
            if (storage.exists(key)) {
                locatedHolders.put(key, storage);
                return storage;
            }
        }
        return last;
    }
}
