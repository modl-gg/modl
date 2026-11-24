package gg.modl.backend.settings.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.service.SettingsDocumentService.RawSettingsState;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;

public final class CachedSettingsDocument<T> {
    private final String type;
    private final SettingsCodec<T> codec;
    private final SettingsDocumentService documents;
    private final UnitOfWork unitOfWork;
    private final AtomicReference<Snapshot<T>> snapshot = new AtomicReference<>();

    public CachedSettingsDocument(SettingsType type, SettingsCodec<T> codec, SettingsDocumentService documents,
                                  UnitOfWork unitOfWork) {
        this.type = type.id();
        this.codec = codec;
        this.documents = documents;
        this.unitOfWork = unitOfWork;
    }

    public T current() {
        return versioned().data();
    }

    public VersionedSettings<T> versioned() {
        Snapshot<T> cached = snapshot.get();
        if (cached != null) {
            return cached.settings();
        }
        return publish(snapshotOf(documents.getRawState(type))).settings();
    }

    public T update(UnaryOperator<T> change) {
        return write(encoded -> documents.updateLatest(type, encoded), change).data();
    }

    public VersionedSettings<T> updateExpecting(long expectedVersion, UnaryOperator<T> change) {
        return write(encoded -> documents.updateExpecting(type, expectedVersion, encoded), change);
    }

    private VersionedSettings<T> write(Function<UnaryOperator<Map<String, Object>>, RawSettingsState> writer,
                                       UnaryOperator<T> change) {
        return unitOfWork.write(() -> {
            RawSettingsState written = writer.apply(current -> codec.encode(change.apply(codec.decode(current))));
            Snapshot<T> committed = snapshotOf(written);
            unitOfWork.afterCommit(() -> publish(committed));
            return committed.settings();
        });
    }

    private Snapshot<T> publish(Snapshot<T> candidate) {
        return snapshot.accumulateAndGet(candidate, Snapshot::newer);
    }

    private Snapshot<T> snapshotOf(RawSettingsState state) {
        return new Snapshot<>(new VersionedSettings<>(codec.decode(state.data()), state.version(), state.updatedAt()));
    }

    private record Snapshot<T>(VersionedSettings<T> settings) {
        static <T> Snapshot<T> newer(Snapshot<T> current, Snapshot<T> candidate) {
            return current == null || candidate.settings().version() >= current.settings().version() ? candidate : current;
        }
    }
}
