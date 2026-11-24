package gg.modl.backend.staff.service;

import org.mockito.ArgumentCaptor;
import java.util.List;
import gg.modl.proto.modl.v1.SyncStaff2faVerification;
import gg.modl.backend.infrastructure.exception.ValidationException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.ImmediateUnitOfWork;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StaffTwoFactorServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final String TOKEN = "plain-token";
    private static final String MINECRAFT_UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private final Server server = TestServers.server("server", "admin@example.com");
    private final OneTimeCodeCodec codec = new OneTimeCodeCodec(() -> "secret");
    private StaffRepository staffRepository;
    private RealtimeEventPublisher realtimeEventPublisher;
    private StaffTwoFactorService service;

    @BeforeEach
    void setUp() {
        staffRepository = mock(StaffRepository.class);
        realtimeEventPublisher = mock(RealtimeEventPublisher.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        when(idGenerator.generateToken()).thenReturn(TOKEN);
        PublicUrls publicUrls = mock(PublicUrls.class);
        when(publicUrls.staffVerify(TOKEN)).thenReturn("https://panel.example.com/verify/" + TOKEN);
        service = new StaffTwoFactorService(staffRepository, publicUrls, codec, idGenerator, realtimeEventPublisher,
            new ImmediateUnitOfWork(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void onlyTheHashOfTheTokenIsStoredWhileTheLinkCarriesThePlainToken() {
        when(staffRepository.createTwoFactorToken(eq(MINECRAFT_UUID), anyString(), anyString(), any(Instant.class))).thenReturn(true);

        Optional<StaffTwoFactorService.TwoFactorTokenResult> result =
            service.generateToken(server, MINECRAFT_UUID.toUpperCase(Locale.ROOT), "127.0.0.1");

        assertThat(result).hasValueSatisfying(issued -> assertThat(issued.verifyUrl()).endsWith("/verify/" + TOKEN));
        verify(staffRepository).createTwoFactorToken(MINECRAFT_UUID, codec.hash(TOKEN), "127.0.0.1", NOW);
    }

    @Test
    void theTokenAddressIsStoredWithoutItsZone() {
        service.generateToken(server, MINECRAFT_UUID, " fe80::1%" + "z".repeat(300) + " ");

        verify(staffRepository).createTwoFactorToken(MINECRAFT_UUID, codec.hash(TOKEN), "fe80::1", NOW);
    }

    @Test
    void theStaffMemberTheTokenWasIssuedForVerifiesItAndMinecraftIsTold() {
        givenTokenHolder(staff(NOW));
        when(staffRepository.activateTwoFactorSession(eq("staff-1"), eq(codec.hash(TOKEN)), eq("127.0.0.1"), any(Instant.class)))
            .thenReturn(true);

        assertThat(service.verify(server, TOKEN, "Staff@Example.com")).isTrue();

        verify(staffRepository).activateTwoFactorSession("staff-1", codec.hash(TOKEN), "127.0.0.1",
            NOW.plus(Duration.ofDays(7)));
        verify(realtimeEventPublisher).pushStaff2fa(eq(server), any(), eq(codec.hash(TOKEN)));
    }

    @Test
    void anotherSignedInAccountCannotVerifySomeoneElsesToken() {
        givenTokenHolder(staff(NOW));

        assertThat(service.verify(server, TOKEN, "admin@example.com")).isFalse();

        verify(staffRepository, never()).activateTwoFactorSession(any(), any(), any(), any());
        verifyNoInteractions(realtimeEventPublisher);
    }

    @Test
    void anExpiredTokenIsRejected() {
        givenTokenHolder(staff(NOW.minus(Duration.ofMinutes(10)).minusSeconds(1)));

        assertThat(service.verify(server, TOKEN, "staff@example.com")).isFalse();

        verify(staffRepository, never()).activateTwoFactorSession(any(), any(), any(), any());
    }

    @Test
    void aTokenUsedConcurrentlyActivatesOnlyOnce() {
        givenTokenHolder(staff(NOW));
        when(staffRepository.activateTwoFactorSession(any(), any(), any(), any())).thenReturn(false);

        assertThat(service.verify(server, TOKEN, "staff@example.com")).isFalse();

        verifyNoInteractions(realtimeEventPublisher);
    }

    @Test
    void thePlainTokenIsNeverUsedAsALookupKey() {
        assertThat(service.verify(server, TOKEN, "staff@example.com")).isFalse();

        verify(staffRepository).findByTwoFactorTokenHash(codec.hash(TOKEN));
        verify(staffRepository, never()).findByTwoFactorTokenHash(TOKEN);
    }

    @Test
    void aConnectionWithoutAnAddressGetsNoLink() {
        assertThatThrownBy(() -> service.generateToken(server, MINECRAFT_UUID, " ")).isInstanceOf(ValidationException.class);

        verify(staffRepository, never()).createTwoFactorToken(any(), any(), any(), any());
    }

    @Test
    void aRequestThatNamesNoConnectionCanNeverBeApproved() {
        Staff withoutAddress = staff(NOW);
        withoutAddress.setTwoFactorTokenIp(null);
        givenTokenHolder(withoutAddress);

        assertThat(service.pendingApproval(server, TOKEN, "staff@example.com")).isEmpty();
        assertThat(service.verify(server, TOKEN, "staff@example.com")).isFalse();

        verify(staffRepository, never()).activateTwoFactorSession(any(), any(), any(), any());
        verifyNoInteractions(realtimeEventPublisher);
    }

    @Test
    void theRequestShowsItsConnectionWithoutApprovingAndTheApprovalCarriesIt() {
        givenTokenHolder(staff(NOW));
        when(staffRepository.activateTwoFactorSession(any(), any(), any(), any())).thenReturn(true);

        assertThat(service.pendingApproval(server, TOKEN, "staff@example.com"))
            .contains(new StaffTwoFactorService.PendingApproval("127.0.0.1", NOW.toEpochMilli()));
        verify(staffRepository, never()).activateTwoFactorSession(any(), any(), any(), any());

        assertThat(service.verify(server, TOKEN, "staff@example.com")).isTrue();

        ArgumentCaptor<List<SyncStaff2faVerification>> pushed = ArgumentCaptor.captor();
        verify(realtimeEventPublisher).pushStaff2fa(eq(server), pushed.capture(), eq(codec.hash(TOKEN)));
        assertThat(pushed.getValue()).singleElement().satisfies(approval -> {
            assertThat(approval.getMinecraftUuid()).isEqualTo(MINECRAFT_UUID);
            assertThat(approval.getIp()).isEqualTo("127.0.0.1");
        });
    }

    private void givenTokenHolder(Staff staff) {
        when(staffRepository.findByTwoFactorTokenHash(codec.hash(TOKEN))).thenReturn(Optional.of(staff));
    }

    private static Staff staff(Instant tokenCreatedAt) {
        return Staff.builder()
            .id("staff-1")
            .email("staff@example.com")
            .assignedMinecraftUuid(MINECRAFT_UUID)
            .twoFactorTokenCreatedAt(tokenCreatedAt.toEpochMilli())
            .twoFactorTokenIp("127.0.0.1")
            .build();
    }
}
