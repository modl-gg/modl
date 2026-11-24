package gg.modl.minecraft.core.config;

import gg.modl.minecraft.core.config.yaml.CoreManagedConfigs;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.YamlValues;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Data
public class PunishGuiConfig {
    public static final int MAX_PUNISHMENT_SLOTS = 14;
    private static final String SECTION = "punish_gui";
    private final Map<Integer, PunishSlotConfig> slots = new HashMap<>();

    @Data @NoArgsConstructor
    public static class PunishSlotConfig {
        private String item = "minecraft:paper", title = "Unknown";
        private List<String> description = new ArrayList<>();
        private int slotNumber, ordinal = 0;
        private boolean enabled = false;
    }

    public static PunishGuiConfig load(Path dataDirectory, PluginLogger logger) {
        try {
            Map<?, ?> section = GuiSections.configured(dataDirectory, CoreManagedConfigs.PUNISH_GUI, SECTION);
            if (section == null) {
                logger.debug("No punish_gui section found, using defaults");
                return createDefault();
            }
            PunishGuiConfig config = parse(section, logger::warning);
            logger.debug("Loaded " + config.slots.size() + " punishment slots from config");
            return config;
        } catch (IOException | RuntimeException e) {
            logger.warning("Failed to load config: " + e.getMessage());
            return createDefault();
        }
    }

    public static PunishGuiConfig createDefault() {
        return parse(GuiSections.packaged(CoreManagedConfigs.PUNISH_GUI, SECTION), problem -> {
            throw new IllegalStateException("The packaged punish_gui.yml is invalid: " + problem);
        });
    }

    @SuppressWarnings("unchecked")
    private static PunishGuiConfig parse(Map<?, ?> section, Consumer<String> problems) {
        PunishGuiConfig config = new PunishGuiConfig();
        for (Map.Entry<?, ?> entry : section.entrySet()) {
            try {
                int slotNumber;
                Object key = entry.getKey();
                if (key instanceof Integer) slotNumber = (Integer) key;
                else if (key instanceof String) slotNumber = Integer.parseInt((String) key);
                else continue;

                if (slotNumber < 1 || slotNumber > MAX_PUNISHMENT_SLOTS) continue;

                Object value = entry.getValue();
                if (!(value instanceof Map)) {
                    problems.accept("Skipping punish GUI slot " + slotNumber + ": expected a mapping but got "
                            + (value == null ? "null" : value.getClass().getSimpleName()));
                    continue;
                }

                config.slots.put(slotNumber, parseSlotConfig(slotNumber, (Map<String, Object>) value));
            } catch (RuntimeException e) {
                problems.accept("Skipping invalid punish GUI slot '" + entry.getKey() + "': " + e.getMessage());
            }
        }
        return config;
    }

    @SuppressWarnings("unchecked")
    private static PunishSlotConfig parseSlotConfig(int slotNumber, Map<String, Object> data) {
        PunishSlotConfig config = new PunishSlotConfig();
        config.setSlotNumber(slotNumber);

        if (data.containsKey("enabled")) config.setEnabled(YamlValues.coerceBoolean(data.get("enabled"), config.isEnabled()));
        if (data.containsKey("item")) config.setItem(YamlValues.coerceString(data.get("item"), config.getItem()));
        if (data.containsKey("title")) config.setTitle(YamlValues.coerceString(data.get("title"), config.getTitle()));
        if (data.containsKey("ordinal")) {
            Object val = data.get("ordinal");
            if (val instanceof Number) config.setOrdinal(((Number) val).intValue());
            else if (val instanceof String) {
                try { config.setOrdinal(Integer.parseInt((String) val)); } catch (NumberFormatException ignored) {}
            }
        }
        if (data.containsKey("description")) {
            Object desc = data.get("description");
            if (desc instanceof List) config.setDescription(new ArrayList<>((List<String>) desc));
        }

        return config;
    }

    public List<PunishSlotConfig> getEnabledSlots() {
        return slots.values().stream()
                .filter(PunishSlotConfig::isEnabled)
                .sorted(Comparator.comparingInt(PunishSlotConfig::getSlotNumber))
                .collect(Collectors.toList());
    }

    public @Nullable PunishSlotConfig getSlot(int slotNumber) {
        return slots.get(slotNumber);
    }
}
