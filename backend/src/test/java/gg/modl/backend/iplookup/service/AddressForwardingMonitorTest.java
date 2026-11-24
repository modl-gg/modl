package gg.modl.backend.iplookup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.iplookup.settings.AddressForwardingNotice;
import gg.modl.backend.iplookup.settings.PlayerAddressSettingsStore;
import gg.modl.backend.support.MutableClock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AddressForwardingMonitorTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final PlayerAddressSettingsStore store = mock(PlayerAddressSettingsStore.class);
    private final AtomicReference<AddressForwardingNotice> notice = new AtomicReference<>(AddressForwardingNotice.NONE);
    private final AddressForwardingMonitor monitor = new AddressForwardingMonitor(store, new MutableClock(NOW));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void inMemoryNotice() {
        when(store.notice()).thenAnswer(invocation -> notice.get());
        when(store.updateNotice(any())).thenAnswer(invocation ->
            notice.updateAndGet(invocation.getArgument(0, UnaryOperator.class)));
    }

    @Test
    void publicAddressesRaiseNoNotice() {
        monitor.observeLogin("203.0.113.7", "lobby");
        monitor.observeLogin(null, "lobby");

        assertThat(notice.get().detected()).isFalse();
    }

    @Test
    void theFirstNonPublicAddressIsRecordedOnceWithItsServer() {
        monitor.observeLogin("172.18.0.3", "lobby");
        monitor.observeLogin("127.0.0.1", "survival");
        monitor.sharedAddressPaused();

        assertThat(notice.get().reason()).isEqualTo(AddressForwardingNotice.Reason.NON_PUBLIC_ADDRESS);
        assertThat(notice.get().serverName()).isEqualTo("lobby");
        assertThat(notice.get().detectedAt()).isEqualTo(NOW.toEpochMilli());
        verify(store, times(1)).updateNotice(any());
    }

    @Test
    void aDismissedNoticeIsNeverRaisedAgain() {
        notice.set(AddressForwardingNotice.detected(AddressForwardingNotice.Reason.SHARED_ADDRESS, null, NOW).dismissedAt(NOW));

        monitor.observeLogin("10.0.0.2", "lobby");

        assertThat(notice.get().showing()).isFalse();
    }
}
