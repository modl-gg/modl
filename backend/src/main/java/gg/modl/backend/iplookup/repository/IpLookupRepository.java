package gg.modl.backend.iplookup.repository;

import gg.modl.backend.iplookup.model.IpLookup;
import java.time.Instant;
import java.util.Optional;

public interface IpLookupRepository {
    Optional<IpLookup> find(String ip);

    void save(IpLookup lookup);

    long count();

    int deleteOldest(int batchSize);

    int deleteLookedUpBefore(Instant cutoff, int batchSize);
}
