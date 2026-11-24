package gg.modl.backend.auth.settings;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class SignInSettingsStub {
    private SignInSettingsStub() {
    }

    public static SignInSettingsService emailCode() {
        return with(SignInSettings.DEFAULTS);
    }

    public static SignInSettingsService with(SignInSettings settings) {
        SignInSettingsService service = mock(SignInSettingsService.class);
        when(service.current()).thenReturn(settings);
        when(service.stored()).thenReturn(settings);
        doCallRealMethod().when(service).requireMethod(any());
        return service;
    }
}
