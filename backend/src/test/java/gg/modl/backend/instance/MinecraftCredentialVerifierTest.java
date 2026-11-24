package gg.modl.backend.instance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.serverkey.ServerKey;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.backend.serverkey.service.ServerKeyCache;
import gg.modl.backend.serverkey.service.ServerKeyLastSeen;
import gg.modl.backend.serverkey.service.ServerKeySecrets;
import gg.modl.backend.support.TestServers;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MinecraftCredentialVerifierTest {
    private final ServerKeyCache serverKeys = mock(ServerKeyCache.class);
    private final ServerKeyLastSeen lastSeen = mock(ServerKeyLastSeen.class);

    @Test
    void theInstanceKeyAndTheInternalKeyAreFullCredentials() {
        MinecraftCredentialVerifier verifier = verifier("modl_instance-key", "internal-key");

        assertThat(verifier.verify("modl_instance-key")).contains(MinecraftCredential.instanceKey());
        assertThat(verifier.verify("internal-key")).contains(MinecraftCredential.internalKey());
        assertThat(MinecraftCredential.internalKey().scope()).isEqualTo(ServerKeyScope.FULL);
        verify(serverKeys, never()).findByHash(any());
    }

    @Test
    void eachCredentialHasItsOwnRateLimitIdentity() {
        assertThat(MinecraftCredential.instanceKey().rateLimitIdentity()).isEqualTo("INSTANCE_KEY");
        assertThat(MinecraftCredential.internalKey().rateLimitIdentity()).isEqualTo("INTERNAL_KEY");
        assertThat(new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, "lobby-key", "lobby").rateLimitIdentity())
            .isEqualTo("key:lobby-key");
    }

    @Test
    void unknownBlankAndMissingKeysAreRefused() {
        MinecraftCredentialVerifier verifier = verifier("modl_instance-key", null);

        assertThat(verifier.verify("modl_instance-keyx")).isEmpty();
        assertThat(verifier.verify("")).isEmpty();
        assertThat(verifier.verify(null)).isEmpty();
        assertThat(verifier(null, null).verify("anything")).isEmpty();
    }

    @Test
    void aServerKeyIsLookedUpByItsHashAndKeepsItsScope() {
        ServerKeySecrets.IssuedSecret issued = new ServerKeySecrets().issue();
        ServerKey key = key("lobby-key", "lobby", ServerKeyScope.BEHIND_PROXY, issued.hash());
        when(serverKeys.findByHash(issued.hash())).thenReturn(Optional.of(key));

        Optional<MinecraftCredential> credential = verifier("modl_instance-key", null).verify(issued.secret());

        assertThat(credential).contains(new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, "lobby-key", "lobby"));
        assertThat(credential.orElseThrow().isMemberOnly()).isTrue();
        verify(lastSeen).touch("lobby-key");
    }

    @Test
    void anUnknownOrRevokedServerKeyIsRefused() {
        ServerKeySecrets.IssuedSecret issued = new ServerKeySecrets().issue();
        when(serverKeys.findByHash(issued.hash())).thenReturn(Optional.empty());

        assertThat(verifier("modl_instance-key", null).verify(issued.secret())).isEmpty();
        verify(lastSeen, never()).touch(any());
    }

    @Test
    void malformedServerKeysNeverReachTheDatabase() {
        MinecraftCredentialVerifier verifier = verifier("modl_instance-key", null);

        assertThat(verifier.verify("msk_short")).isEmpty();
        assertThat(verifier.verify("msk_" + "a".repeat(500))).isEmpty();
        verify(serverKeys, never()).findByHash(any());
    }

    private MinecraftCredentialVerifier verifier(String instanceKey, String internalKey) {
        Server server = TestServers.server("Server", "owner@example.com");
        server.setApiKey(instanceKey);
        InstanceContext instanceContext = mock(InstanceContext.class);
        when(instanceContext.instance()).thenReturn(server);
        InstanceProperties properties = new InstanceProperties(null, null, null, null, internalKey, null);
        return new MinecraftCredentialVerifier(instanceContext, properties, serverKeys, lastSeen);
    }

    static ServerKey key(String id, String name, ServerKeyScope scope, String hash) {
        return new ServerKey(id, name, scope, false, hash, "abcdefgh", "owner@example.com", Instant.EPOCH, null);
    }
}
