package gg.modl.backend.support;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.iplookup.settings.PlayerAddressSettings;
import gg.modl.backend.iplookup.settings.PlayerAddressSettingsStore;

public final class TestAddressSettings {
    private TestAddressSettings() {
    }

    public static PlayerAddressSettingsStore defaults() {
        PlayerAddressSettingsStore store = mock(PlayerAddressSettingsStore.class);
        when(store.current()).thenReturn(PlayerAddressSettings.EMPTY);
        return store;
    }
}
