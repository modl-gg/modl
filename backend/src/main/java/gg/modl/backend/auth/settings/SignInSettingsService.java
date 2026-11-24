package gg.modl.backend.auth.settings;

import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.database.UnitFrame;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.service.SettingsDocumentService;
import gg.modl.backend.settings.service.SettingsDocumentService.RawSettingsState;
import gg.modl.backend.settings.service.VersionedSettings;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SignInSettingsService {
    static final String SETTINGS_TYPE = SettingsType.SIGN_IN.id();

    private final SettingsDocumentService documentService;
    private final UnitOfWork unitOfWork;
    private final ReentrantLock snapshotLock = new ReentrantLock();
    @Nullable
    private volatile SignInSettings snapshot;

    public SignInSettings current() {
        SignInSettings cached = snapshot;
        if (cached != null) {
            return cached;
        }
        if (UnitFrame.current().isPresent()) {
            return stored();
        }
        snapshotLock.lock();
        try {
            if (snapshot == null) {
                snapshot = stored();
            }
            return snapshot;
        } finally {
            snapshotLock.unlock();
        }
    }

    public SignInSettings stored() {
        return state().data();
    }

    public VersionedSettings<SignInSettings> state() {
        RawSettingsState raw = documentService.getRawState(SETTINGS_TYPE);
        return new VersionedSettings<>(SignInSettings.decode(raw.data()), raw.version(), raw.updatedAt());
    }

    public void requireMethod(SignInMethod method) {
        if (current().method() != method) {
            throw AuthFailures.signInMethodDisabled();
        }
    }

    public VersionedSettings<SignInSettings> replace(long expectedVersion, SignInSettings settings) {
        return write(() -> documentService.updateExpecting(SETTINGS_TYPE, expectedVersion, ignored -> settings.encode()));
    }

    public void applyFromSetup(SignInMethod method) {
        write(() -> documentService.updateLatest(SETTINGS_TYPE, preservingRequirement(method)));
    }

    private static UnaryOperator<Map<String, Object>> preservingRequirement(SignInMethod method) {
        return data -> SignInSettings.decode(data).withMethod(method).encode();
    }

    private VersionedSettings<SignInSettings> write(Supplier<RawSettingsState> change) {
        return unitOfWork.write(() -> {
            RawSettingsState saved = change.get();
            unitOfWork.afterCommit(this::forgetSnapshot);
            return new VersionedSettings<>(SignInSettings.decode(saved.data()), saved.version(), saved.updatedAt());
        });
    }

    private void forgetSnapshot() {
        snapshotLock.lock();
        try {
            snapshot = null;
        } finally {
            snapshotLock.unlock();
        }
    }
}
