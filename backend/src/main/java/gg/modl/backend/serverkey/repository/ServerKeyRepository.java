package gg.modl.backend.serverkey.repository;

import gg.modl.backend.serverkey.ServerKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ServerKeyRepository {
    boolean insertIfAbsent(ServerKey key);

    Optional<ServerKey> findByKeyHash(String keyHash);

    Optional<ServerKey> findById(String id);

    boolean existsByName(String name);

    List<ServerKey> findAll();

    int count();

    boolean setAllowProxyCommands(String id, boolean allow);

    boolean delete(String id);

    boolean recordSeen(String id, Instant seenAt, Instant onlyIfSeenBefore);
}
