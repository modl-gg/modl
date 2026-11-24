package gg.modl.backend.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.instance.InstanceSecretsService;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.OwnershipMarker;
import gg.modl.backend.storage.StorageNamespace;
import gg.modl.backend.storage.local.LocalObjectStorage;
import gg.modl.backend.storage.local.UploadSigner;
import gg.modl.backend.update.commit.InstanceCommittedEvent;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.event.EventListener;

class StorageOwnershipTest {
    private static final String NAMESPACE = StorageNamespace.NAME;

    @TempDir
    Path root;

    private ObjectStorage storage;
    private InstanceSecretsService secrets;

    @BeforeEach
    void setUp() {
        storage = new LocalObjectStorage(root, new UploadSigner(() -> "signing-key"), Clock.systemUTC());
        secrets = mock(InstanceSecretsService.class);
        when(secrets.storageSigningKey()).thenReturn("installation-a");
    }

    @Test
    void anEmptyNamespaceIsClaimedOnStartup() {
        ownership(secrets).claimAfterCommit();

        assertThat(storage.readOwnershipMarker(NAMESPACE)).isPresent();
        assertThat(ownership(secrets).verify().isOwned()).isTrue();
    }

    @Test
    void theMarkerIsWrittenOnlyAfterAnUpdateCommits() throws NoSuchMethodException {
        assertThat(SmartInitializingSingleton.class.isAssignableFrom(StorageOwnership.class)).isFalse();
        assertThat(StorageOwnership.class.getMethod("claimAfterCommit").getAnnotation(EventListener.class).value())
            .containsExactly(InstanceCommittedEvent.class);
        assertThat(storage.readOwnershipMarker(NAMESPACE)).isEmpty();
    }

    @Test
    void aNamespaceClaimedByAnotherInstallationIsRefused() {
        ownership(secrets).claimAfterCommit();
        InstanceSecretsService otherSecrets = mock(InstanceSecretsService.class);
        when(otherSecrets.storageSigningKey()).thenReturn("installation-b");

        StorageOwnerMarker.Verdict verdict = ownership(otherSecrets).verify();

        assertThat(verdict.isOwned()).isFalse();
        assertThat(verdict.refusalReason()).contains("another modl installation");
    }

    @Test
    void anUnmarkedNamespaceWithObjectsIsRefusedAndNotClaimed() {
        storage.write(NAMESPACE + "/tickets/T-1/a.png", new ByteArrayInputStream(new byte[] {1}), 1, "image/png");

        StorageOwnerMarker.Verdict verdict = ownership(secrets).verify();

        assertThat(verdict.isOwned()).isFalse();
        assertThat(verdict.refusalReason()).contains(OwnershipMarker.key(NAMESPACE));
        assertThat(storage.readOwnershipMarker(NAMESPACE)).isEmpty();
    }

    @Test
    void theMarkerIsNeverListedAsAnObject() {
        ownership(secrets).claimAfterCommit();

        assertThat(storage.list(NAMESPACE + "/")).isEmpty();
    }

    private StorageOwnership ownership(InstanceSecretsService instanceSecrets) {
        return new StorageOwnership(storage, new StorageOwnerMarker(instanceSecrets));
    }
}
