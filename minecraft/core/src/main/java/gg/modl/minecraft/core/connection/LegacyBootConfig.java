package gg.modl.minecraft.core.connection;

import gg.modl.minecraft.core.boot.PlatformType;
import gg.modl.minecraft.core.util.YamlValues;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class LegacyBootConfig {
    static final String FILE_NAME = "boot.yml";
    static final String RENAMED_FILE_NAME = "boot.yml.old";

    private static final List<String> LEGACY_MEMBER_MODES = Arrays.asList("bridge-only", "bridge");

    private LegacyBootConfig() {
    }

    static Map<String, Object> connectionValues(Map<?, ?> bootYml, PlatformType platform) {
        Map<String, Object> values = new LinkedHashMap<>();
        boolean member = !platform.isProxy() && isMemberMode(YamlValues.coerceString(bootYml.get("mode"), ""));
        values.put(ConnectionYaml.MODE, member ? ConnectionMode.BEHIND_PROXY : ConnectionMode.EMBEDDED);
        return values;
    }

    private static boolean isMemberMode(String mode) {
        return LEGACY_MEMBER_MODES.contains(mode.trim().toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
