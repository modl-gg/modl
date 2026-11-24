package gg.modl.backend.auth.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.ImmediateUnitOfWork;
import gg.modl.backend.auth.repository.MinecraftLinkCodeRepository;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.service.MinecraftLinkOutcome;
import gg.modl.backend.staff.service.MinecraftStaffService;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MinecraftAccountLinkServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final String UUID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String EMAIL = "mod@example.com";
    private static final String CODE = "ABCD2345";

    private MinecraftLinkCodeRepository repository;
    private MinecraftStaffService minecraftStaffService;
    private OneTimeCodeCodec codec;
    private MinecraftAccountLinkService service;
    private Server server;

    @BeforeEach
    void setUp() {
        repository = mock(MinecraftLinkCodeRepository.class);
        minecraftStaffService = mock(MinecraftStaffService.class);
        codec = new OneTimeCodeCodec(() -> "secret");
        server = TestServers.server("Server", "owner@example.com");
        service = new MinecraftAccountLinkService(repository, minecraftStaffService, codec, new ImmediateUnitOfWork(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void issuedCodesAreStoredHashedForTheNormalizedIdentity() {
        LinkCodeIssue issue = service.issueCode(server, "Mod@Example.com");

        assertThat(issue.command()).isEqualTo("/modl link " + issue.code());
        assertThat(issue.expiresInSeconds()).isEqualTo(600);
        ArgumentCaptor<MinecraftLinkCode> stored = ArgumentCaptor.forClass(MinecraftLinkCode.class);
        verify(repository).replaceForIdentity(stored.capture());
        assertThat(stored.getValue().getId()).isNotBlank();
        assertThat(stored.getValue().getIdentityEmail()).isEqualTo(EMAIL);
        assertThat(stored.getValue().getCodeHash()).isEqualTo(codec.hash(issue.code()));
        assertThat(stored.getValue().getExpiresAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    void unknownCodesAreRejected() {
        when(repository.findActive(anyString(), any())).thenReturn(Optional.empty());

        assertThat(service.redeem(server, CODE, UUID, "Steve")).isEqualTo(AccountLinkResult.INVALID_CODE);
        verify(repository, never()).consumeActive(any(), any());
    }

    @Test
    void anAccountLinkedToAnotherIdentityIsReportedWithoutConsumingTheCode() {
        when(repository.findActive(codec.hash(CODE), NOW)).thenReturn(Optional.of(code()));
        when(minecraftStaffService.isAssignedToAnotherIdentity(server, EMAIL, UUID)).thenReturn(true);

        assertThat(service.redeem(server, CODE, UUID, "Steve")).isEqualTo(AccountLinkResult.ALREADY_LINKED);
        verify(repository, never()).consumeActive(any(), any());
        verify(minecraftStaffService, never()).linkOwnMinecraftAccount(any(), any(), any(), any());
    }

    @Test
    void aValidCodeIsConsumedAndLinksTheIssuingIdentity() {
        when(repository.findActive(codec.hash(CODE), NOW)).thenReturn(Optional.of(code()));
        when(repository.consumeActive(codec.hash(CODE), NOW)).thenReturn(Optional.of(code()));
        when(minecraftStaffService.linkOwnMinecraftAccount(server, EMAIL, UUID, "Steve")).thenReturn(MinecraftLinkOutcome.LINKED);

        assertThat(service.redeem(server, "abcd-2345", UUID, "Steve")).isEqualTo(AccountLinkResult.LINKED);
    }

    @Test
    void aCodeConsumedConcurrentlyIsRejected() {
        when(repository.findActive(codec.hash(CODE), NOW)).thenReturn(Optional.of(code()));
        when(repository.consumeActive(codec.hash(CODE), NOW)).thenReturn(Optional.empty());

        assertThat(service.redeem(server, CODE, UUID, "Steve")).isEqualTo(AccountLinkResult.INVALID_CODE);
        verify(minecraftStaffService, never()).linkOwnMinecraftAccount(any(), any(), any(), any());
    }

    @Test
    void aMalformedAccountIsRejectedWithoutReadingAnyCode() {
        assertThatThrownBy(() -> service.redeem(server, CODE, "not-a-uuid", "Steve")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.redeem(server, CODE, UUID, "not a name")).isInstanceOf(ValidationException.class);
        verifyNoInteractions(repository, minecraftStaffService);
    }

    private static MinecraftLinkCode code() {
        return MinecraftLinkCode.builder().identityEmail(EMAIL).build();
    }
}
