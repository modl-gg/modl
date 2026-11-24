package gg.modl.backend.staff.service;

import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.util.LoadOutsideCache;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StaffLookupCache {
    private final StaffRepository staffRepository;

    private final LoadOutsideCache<String, Optional<Staff>> staffByEmailCache = new LoadOutsideCache<>(1000, Duration.ofMinutes(2));

    public Optional<Staff> findByEmail(Server server, String email) {
        String normalized = EmailAddressUtil.normalize(email);
        if (normalized == null) {
            return Optional.empty();
        }
        String cacheKey = server.getId() + ":" + normalized;
        return staffByEmailCache.get(cacheKey, () -> staffRepository.findByEmail(normalized));
    }

    public void evict(Server server, String email) {
        String normalized = EmailAddressUtil.normalize(email);
        if (normalized == null) {
            return;
        }
        staffByEmailCache.invalidate(server.getId() + ":" + normalized);
    }

    public void evictAll() {
        staffByEmailCache.invalidateAll();
    }
}
