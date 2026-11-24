package gg.modl.backend.serverkey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.serverkey.ServerKey;
import gg.modl.backend.serverkey.ServerKeyChangedEvent;
import gg.modl.backend.serverkey.ServerKeyErrors;
import gg.modl.backend.serverkey.ServerKeyRevokedEvent;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.backend.serverkey.repository.ServerKeyRepository;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.support.TestServers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class ServerKeyServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final Server SERVER = TestServers.server("Server", "owner@example.com");

    private final ServerKeyRepository repository = mock(ServerKeyRepository.class);
    private final PanelActionAuditor auditor = mock(PanelActionAuditor.class);
    private final DeferredUnitOfWork unitOfWork = new DeferredUnitOfWork();
    private final List<Object> events = new ArrayList<>();
    private final ApplicationEventPublisher publisher = events::add;
    private final ServerKeyService service =
        new ServerKeyService(repository, new ServerKeySecrets(), unitOfWork, publisher, auditor, new MutableClock(NOW));

    @Test
    void aCreatedKeyStoresOnlyTheHashAndReturnsTheSecretOnce() {
        when(repository.insertIfAbsent(any())).thenReturn(true);

        CreatedServerKey created = service.create(SERVER, "lobby", ServerKeyScope.BEHIND_PROXY, true, "owner@example.com");

        assertThat(created.secret()).startsWith(ServerKeySecrets.PREFIX);
        assertThat(created.key().keyHash()).isEqualTo(ServerKeySecrets.hash(created.secret()));
        assertThat(created.key().allowProxyCommands()).isTrue();
        assertThat(created.key().createdAt()).isEqualTo(NOW);
        assertThat(created.toString()).doesNotContain(created.secret());
        verify(repository).insertIfAbsent(created.key());
    }

    @Test
    void aProxyOrStandaloneKeyNeverStoresTheProxyCommandSetting() {
        when(repository.insertIfAbsent(any())).thenReturn(true);

        CreatedServerKey created = service.create(SERVER, "proxy", ServerKeyScope.FULL, true, "owner@example.com");

        assertThat(created.key().allowProxyCommands()).isFalse();
    }

    @Test
    void eventsAndTheAuditEntryWaitForTheCommit() {
        when(repository.insertIfAbsent(any())).thenReturn(true);

        CreatedServerKey created = service.create(SERVER, "lobby", ServerKeyScope.BEHIND_PROXY, false, "owner@example.com");

        assertThat(events).isEmpty();
        verifyNoInteractions(auditor);
        unitOfWork.commit();
        assertThat(events).containsExactly(new ServerKeyChangedEvent(created.key()));
        verify(auditor).recordStaffAction(eq(SERVER), eq("owner@example.com"), anyString());
    }

    @Test
    void theLimitIsCheckedInsideTheWriteUnit() {
        when(repository.count()).thenReturn(ServerKeyService.LIMIT);

        assertThatThrownBy(() -> service.create(SERVER, "lobby", ServerKeyScope.BEHIND_PROXY, false, null))
            .isInstanceOf(ConflictException.class)
            .extracting(failure -> ((BaseApplicationException) failure).getMachineCode()).isEqualTo(ServerKeyErrors.LIMIT);
        verify(repository, never()).insertIfAbsent(any());
    }

    @Test
    void aTakenNameIsAConflict() {
        when(repository.existsByName("Lobby")).thenReturn(true);

        assertThatThrownBy(() -> service.create(SERVER, "Lobby", ServerKeyScope.BEHIND_PROXY, false, null))
            .extracting(failure -> ((BaseApplicationException) failure).getMachineCode()).isEqualTo(ServerKeyErrors.NAME_TAKEN);
    }

    @Test
    void aLostInsertRaceIsAlsoANameConflict() {
        when(repository.insertIfAbsent(any())).thenReturn(false);

        assertThatThrownBy(() -> service.create(SERVER, "lobby", ServerKeyScope.BEHIND_PROXY, false, null))
            .extracting(failure -> ((BaseApplicationException) failure).getMachineCode()).isEqualTo(ServerKeyErrors.NAME_TAKEN);
    }

    @Test
    void namesAreValidatedBeforeAnythingIsWritten() {
        for (String name : List.of("", "has space", "x".repeat(65), "naïve")) {
            assertThatThrownBy(() -> service.create(SERVER, name, ServerKeyScope.BEHIND_PROXY, false, null))
                .isInstanceOf(ValidationException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test
    void changingTheProxyCommandSettingPublishesTheChangedKey() {
        ServerKey key = key(ServerKeyScope.BEHIND_PROXY, false);
        when(repository.findById("k1")).thenReturn(Optional.of(key));

        ServerKey changed = service.setAllowProxyCommands(SERVER, "k1", true, "owner@example.com");

        assertThat(changed.allowProxyCommands()).isTrue();
        verify(repository).setAllowProxyCommands("k1", true);
        unitOfWork.commit();
        assertThat(events).containsExactly(new ServerKeyChangedEvent(changed));
    }

    @Test
    void proxyCommandsCannotBeAllowedOnAFullKey() {
        when(repository.findById("k1")).thenReturn(Optional.of(key(ServerKeyScope.FULL, false)));

        assertThatThrownBy(() -> service.setAllowProxyCommands(SERVER, "k1", true, null))
            .extracting(failure -> ((BaseApplicationException) failure).getMachineCode()).isEqualTo(ServerKeyErrors.SCOPE);
        verify(repository, never()).setAllowProxyCommands(anyString(), eq(true));
    }

    @Test
    void revokingDeletesTheRowAndPublishesTheRevocationAfterCommit() {
        ServerKey key = key(ServerKeyScope.BEHIND_PROXY, false);
        when(repository.findById("k1")).thenReturn(Optional.of(key));

        service.revoke(SERVER, "k1", "owner@example.com");

        verify(repository).delete("k1");
        assertThat(events).isEmpty();
        unitOfWork.commit();
        assertThat(events).containsExactly(new ServerKeyRevokedEvent(key));
    }

    @Test
    void anUnknownKeyIsNotFound() {
        assertThatThrownBy(() -> service.revoke(SERVER, "missing", null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.setAllowProxyCommands(SERVER, "missing", false, null))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    private static ServerKey key(ServerKeyScope scope, boolean allowProxyCommands) {
        return new ServerKey("k1", "lobby", scope, allowProxyCommands, "a".repeat(64), "abcdefgh", null, NOW, null);
    }
}
