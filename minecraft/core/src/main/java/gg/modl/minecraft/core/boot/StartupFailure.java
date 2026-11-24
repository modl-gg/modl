package gg.modl.minecraft.core.boot;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class StartupFailure {
    String reason;
    String remedy;
    String loginMessageKey;
    Map<String, String> loginMessagePlaceholders;

    public static StartupFailure of(String reason, String remedy) {
        return withLoginMessage(reason, remedy, CoreState.UNAVAILABLE.refusalMessageKey(),
                Collections.<String, String>emptyMap());
    }

    public static StartupFailure withLoginMessage(String reason, String remedy, String loginMessageKey,
                                                  Map<String, String> loginMessagePlaceholders) {
        return new StartupFailure(reason, remedy, loginMessageKey,
                Collections.unmodifiableMap(new HashMap<>(loginMessagePlaceholders)));
    }
}
