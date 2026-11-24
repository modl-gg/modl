package gg.modl.backend.storage.service;

import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.service.StorageOwnerMarker.Verdict;
import gg.modl.backend.update.commit.InstanceCommittedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class StorageOwnership {
    private final ObjectStorage objectStorage;
    private final StorageOwnerMarker marker;

    @EventListener(InstanceCommittedEvent.class)
    public void claimAfterCommit() {
        try {
            verify();
        } catch (RuntimeException e) {
            log.warn("Could not mark the storage as owned by this modl installation", e);
        }
    }

    public Verdict verify() {
        return marker.claim(objectStorage);
    }
}
