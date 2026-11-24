package gg.modl.backend.iplookup.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.support.TestServers;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlayerAddressSettingsServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String OWNER = "owner@example.com";

    private final Server server = TestServers.server("server", OWNER);
    private final PlayerAddressSettingsStore store = mock(PlayerAddressSettingsStore.class);
    private final PanelActionAuditor auditor = mock(PanelActionAuditor.class);
    private final AtomicReference<PlayerAddressSettings> stored = new AtomicReference<>(PlayerAddressSettings.EMPTY);
    private final AtomicReference<AddressForwardingNotice> notice = new AtomicReference<>(AddressForwardingNotice.NONE);
    private final PlayerAddressSettingsService service = new PlayerAddressSettingsService(store, auditor, new MutableClock(NOW));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void inMemoryStore() {
        when(store.current()).thenAnswer(invocation -> stored.get());
        when(store.update(any())).thenAnswer(invocation ->
            stored.updateAndGet(invocation.getArgument(0, UnaryOperator.class)));
        when(store.notice()).thenAnswer(invocation -> notice.get());
        when(store.updateNotice(any())).thenAnswer(invocation ->
            notice.updateAndGet(invocation.getArgument(0, UnaryOperator.class)));
    }

    @Test
    void aProviderCannotBeChosenBeforeItsKeyIsSaved() {
        assertThatThrownBy(() -> service.selectProvider(server, OWNER, "vpnapi"))
            .isInstanceOf(ValidationException.class)
            .hasFieldOrPropertyWithValue("machineCode", PlayerAddressSettingsService.KEY_MISSING);

        service.saveKey(server, OWNER, "vpnapi", "abc123");
        PlayerAddressSettingsView view = service.selectProvider(server, OWNER, "vpnapi");

        assertThat(view.provider()).isEqualTo(IpLookupProvider.VPNAPI);
        assertThat(view.lookupsActive()).isTrue();
        assertThat(view.providersWithKeys()).containsExactly(IpLookupProvider.VPNAPI);
    }

    @Test
    void keylessProxycheckCanBeChosenWithoutAKeyAndRecordsTheChoice() {
        PlayerAddressSettingsView view = service.selectProvider(server, OWNER, "proxycheck_nokey");

        assertThat(view.provider()).isEqualTo(IpLookupProvider.PROXYCHECK_KEYLESS);
        assertThat(view.lookupsActive()).isTrue();
        assertThat(view.choicePending()).isFalse();
        assertThat(view.providersWithKeys()).isEmpty();
    }

    @Test
    void choosingNoLookupsIsADecisionThatEndsThePrompt() {
        assertThat(service.view().choicePending()).isTrue();

        PlayerAddressSettingsView view = service.selectProvider(server, OWNER, "");

        assertThat(view.provider()).isNull();
        assertThat(view.lookupsActive()).isFalse();
        assertThat(view.choicePending()).isFalse();
        verify(auditor).recordStaffAction(server, OWNER, "Turned player IP lookups off");
    }

    @Test
    void dismissingThePromptKeepsLookupsOff() {
        PlayerAddressSettingsView view = service.dismissChoicePrompt();

        assertThat(view.choicePending()).isFalse();
        assertThat(view.lookupsActive()).isFalse();
        assertThat(stored.get().selectedProvider()).isEmpty();
    }

    @Test
    void theKeylessProviderTakesNoKey() {
        assertThatThrownBy(() -> service.saveKey(server, OWNER, "proxycheck_nokey", "abc123"))
            .hasFieldOrPropertyWithValue("machineCode", PlayerAddressSettingsService.KEY_NOT_USED);
        assertThatThrownBy(() -> service.testAccess("proxycheck_nokey", "abc123"))
            .hasFieldOrPropertyWithValue("machineCode", PlayerAddressSettingsService.KEY_NOT_USED);
        assertThat(service.testAccess("proxycheck_nokey", null).apiKey()).isNull();
    }

    @Test
    void aTestUsesTheTypedKeyOrElseTheSavedOne() {
        assertThatThrownBy(() -> service.testAccess("iphub", null))
            .hasFieldOrPropertyWithValue("machineCode", PlayerAddressSettingsService.KEY_MISSING);
        assertThatThrownBy(() -> service.testAccess("iphub", "has space"))
            .hasFieldOrPropertyWithValue("machineCode", PlayerAddressSettingsService.KEY_INVALID);
        assertThat(service.testAccess("iphub", "typed").apiKey()).isEqualTo("typed");

        service.saveKey(server, OWNER, "iphub", "saved");

        assertThat(service.testAccess("iphub", null).apiKey()).isEqualTo("saved");
    }

    @Test
    void keysWithSpacesOrControlCharactersAreRefused() {
        for (String invalid : new String[] {"", "has space", "tab\tkey", "x".repeat(257)}) {
            assertThatThrownBy(() -> service.saveKey(server, OWNER, "iphub", invalid))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("machineCode", PlayerAddressSettingsService.KEY_INVALID);
        }
        verify(store, never()).update(any());
    }

    @Test
    void anUnknownProviderIsRefused() {
        assertThatThrownBy(() -> service.saveKey(server, OWNER, "ipwhois", "key"))
            .isInstanceOf(ValidationException.class)
            .hasFieldOrPropertyWithValue("machineCode", PlayerAddressSettingsService.PROVIDER_UNKNOWN);
    }

    @Test
    void theSharedAccountLimitIsBounded() {
        assertThatThrownBy(() -> service.setSharedAccountLimit(server, OWNER, 1))
            .hasFieldOrPropertyWithValue("machineCode", PlayerAddressSettingsService.LIMIT_INVALID);

        assertThat(service.setSharedAccountLimit(server, OWNER, 25).sharedAccountLimit()).isEqualTo(25);
        verify(auditor).recordStaffAction(server, OWNER, "Changed the shared-address linking limit from 10 to 25 accounts");
    }

    @Test
    void theForwardingNoticeIsShownUntilDismissed() {
        notice.set(AddressForwardingNotice.detected(AddressForwardingNotice.Reason.NON_PUBLIC_ADDRESS, "lobby", NOW));
        assertThat(service.view().notice().showing()).isTrue();

        assertThat(service.dismissNotice().notice().showing()).isFalse();
        assertThat(notice.get().detected()).isTrue();
    }
}
