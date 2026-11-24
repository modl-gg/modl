package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.util.StringUtil;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class ForwardedSwitches {
    static final String UPDATE_CHECK = "MODL_UPDATE_CHECK";
    static final String BASE_URL = "MODL_UPDATE_BASE_URL";
    static final List<String> FORWARDED = Collections.unmodifiableList(Arrays.asList(
            UPDATE_CHECK, "MODL_PRE_UPDATE_BACKUP", "MODL_ALLOW_DOWNGRADE", BASE_URL, "MODL_UPDATE_API_URL",
            "MODL_DATABASE_REOPEN"));

    private ForwardedSwitches() {
    }

    public static Map<String, String> forwarded(boolean updateChecksEnabled, String downloadUrl) {
        return forwarded(System::getProperty, System::getenv, updateChecksEnabled, downloadUrl);
    }

    static Map<String, String> forwarded(Function<String, String> systemProperties, Function<String, String> environment,
                                         boolean updateChecksEnabled, String downloadUrl) {
        Map<String, String> switches = new LinkedHashMap<>();
        for (String name : FORWARDED) {
            String value = systemProperties.apply(name);
            if (StringUtil.isBlank(value)) value = environment.apply(name);
            if (!StringUtil.isBlank(value)) switches.put(name, value.trim());
        }
        if (!switches.containsKey(BASE_URL) && !StringUtil.isBlank(downloadUrl)) switches.put(BASE_URL, downloadUrl.trim());
        if (!updateChecksEnabled) switches.put(UPDATE_CHECK, "false");
        return Collections.unmodifiableMap(switches);
    }
}
