package gg.modl.backend.auth.settings;

import java.util.LinkedHashMap;
import java.util.Map;

public record SignInSettings(SignInMethod method, boolean secondFactorRequired) {
    public static final SignInSettings DEFAULTS = new SignInSettings(SignInMethod.EMAIL_CODE, false);
    static final String METHOD = "method";
    static final String SECOND_FACTOR_REQUIRED = "secondFactorRequired";

    static SignInSettings decode(Map<String, Object> data) {
        if (data.isEmpty()) {
            return DEFAULTS;
        }
        Object method = data.getOrDefault(METHOD, DEFAULTS.method().name());
        Object required = data.getOrDefault(SECOND_FACTOR_REQUIRED, DEFAULTS.secondFactorRequired());
        if (!(method instanceof String name) || !(required instanceof Boolean flag)) {
            throw new IllegalStateException("The stored sign-in settings cannot be read");
        }
        return new SignInSettings(SignInMethod.valueOf(name), flag);
    }

    Map<String, Object> encode() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(METHOD, method.name());
        data.put(SECOND_FACTOR_REQUIRED, secondFactorRequired);
        return data;
    }

    public SignInSettings withMethod(SignInMethod newMethod) {
        return new SignInSettings(newMethod, secondFactorRequired);
    }
}
