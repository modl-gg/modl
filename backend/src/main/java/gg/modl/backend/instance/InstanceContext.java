package gg.modl.backend.instance;

import gg.modl.backend.instance.repository.InstanceRepository;
import gg.modl.backend.server.data.Server;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InstanceContext {
    private final InstanceRepository instanceRepository;
    private final AtomicLong readSequence = new AtomicLong();
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public Server instance() {
        Snapshot current = snapshot.get();
        return current != null ? current.server() : refresh();
    }

    public Server refresh() {
        long sequence = readSequence.incrementAndGet();
        Server loaded = instanceRepository.find()
            .orElseThrow(() -> new IllegalStateException("The instance has not been bootstrapped"));
        return snapshot.accumulateAndGet(new Snapshot(sequence, loaded), Snapshot::newer).server();
    }

    private record Snapshot(long sequence, Server server) {
        static Snapshot newer(Snapshot current, Snapshot candidate) {
            return current == null || candidate.sequence() > current.sequence() ? candidate : current;
        }
    }
}
