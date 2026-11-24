package gg.modl.backend.storage.repository;

import gg.modl.backend.storage.ReservedBytes;
import gg.modl.backend.storage.UploadOrigin;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface StorageReservationRepository {
    void reserve(StorageReservation reservation);

    Optional<StorageReservation> claim(String key);

    boolean markLanded(String key);

    ReservedBytes sumReserved();

    PrefixUsage usageUnder(String prefix);

    long sumReservedBytes(UploadOrigin origin);

    List<String> findSettleableKeys(Instant now, int limit);

    Set<String> findReservedKeys(Collection<String> keys);

    long release(Collection<String> keys);

    boolean anyCreatedBefore(Instant cutoff);
}
