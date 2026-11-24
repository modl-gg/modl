package gg.modl.backend.log.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import gg.modl.backend.audit.data.AuditLog;
import gg.modl.backend.database.UnitOfWork;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ObservedAuditLogRepositoryTest {
    private final JooqAuditLogRepository delegate = mock(JooqAuditLogRepository.class);
    private final CommitOnDemand unitOfWork = new CommitOnDemand();
    private final List<AuditLog> observed = new ArrayList<>();
    private final ObservedAuditLogRepository repository =
        new ObservedAuditLogRepository(delegate, unitOfWork, List.of(observed::add));

    @Test
    void observersHearOfAnEntryOnlyAfterItsUnitCommits() {
        AuditLog entry = AuditLog.builder().source("Mod").description("Ban issued").build();

        repository.insert(entry);

        verify(delegate).insert(entry);
        assertThat(observed).isEmpty();
        unitOfWork.commit();
        assertThat(observed).containsExactly(entry);
    }

    @Test
    void aRolledBackUnitIsNeverReported() {
        repository.insert(AuditLog.builder().source("Mod").description("Ban issued").build());

        unitOfWork.rollBack();

        assertThat(observed).isEmpty();
    }

    private static final class CommitOnDemand implements UnitOfWork {
        private final List<Runnable> effects = new ArrayList<>();

        @Override
        public <T> T write(Supplier<T> unit) {
            return unit.get();
        }

        @Override
        public void write(Runnable unit) {
            unit.run();
        }

        @Override
        public <T> T read(Supplier<T> unit) {
            return unit.get();
        }

        @Override
        public <T> T report(Supplier<T> unit) {
            return unit.get();
        }

        @Override
        public void afterCommit(Runnable effect) {
            effects.add(effect);
        }

        void commit() {
            List<Runnable> pending = List.copyOf(effects);
            effects.clear();
            pending.forEach(Runnable::run);
        }

        void rollBack() {
            effects.clear();
        }
    }
}
