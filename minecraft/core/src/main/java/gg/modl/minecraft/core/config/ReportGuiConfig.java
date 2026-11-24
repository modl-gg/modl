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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Data
public class ReportGuiConfig {
    private static final String SECTION = "report_gui", INFO = "info";
    private static final int MAX_SLOTS = 14;

    private final Map<Integer, ReportSlotConfig> slots = new HashMap<>();
    private InfoConfig infoConfig = new InfoConfig();

    @Data @NoArgsConstructor
    public static class ReportSlotConfig {
        private String item = "minecraft:paper", title = "Unknown";
        private List<String> description = new ArrayList<>();
        private int slotNumber;
        private boolean enabled = false, chatReport = false, replayCapture = false;
    }

    @Data @NoArgsConstructor
    public static class InfoConfig {
        private String item = "minecraft:oak_sign", title = "&eInformation";
        private List<String> description = new ArrayList<>();
    }

    public static ReportGuiConfig load(Path dataDirectory, PluginLogger logger) {
        try {
            Map<?, ?> section = GuiSections.configured(dataDirectory, CoreManagedConfigs.REPORT_GUI, SECTION);
            if (section == null) {
                logger.debug("No report_gui section found, using defaults");
                return createDefault();
            }
            ReportGuiConfig config = parse(section, logger::warning);
            logger.debug("Loaded " + config.slots.size() + " report slots from config");
            return config;
        } catch (IOException | RuntimeException e) {
            logger.warning("Failed to load config: " + e.getMessage());
            return createDefault();
        }
    }

    public static ReportGuiConfig createDefault() {
        return parse(GuiSections.packaged(CoreManagedConfigs.REPORT_GUI, SECTION), problem -> {
            throw new IllegalStateException("The packaged report_gui.yml is invalid: " + problem);
        });
    }

    @SuppressWarnings("unchecked")
    private static ReportGuiConfig parse(Map<?, ?> section, Consumer<String> problems) {
        ReportGuiConfig config = new ReportGuiConfig();
        for (Map.Entry<?, ?> entry : section.entrySet()) {
            Object key = entry.getKey();
            try {
                if (INFO.equals(key)) {
                    if (!(entry.getValue() instanceof Map)) {
                        problems.accept("Skipping report GUI 'info': expected a mapping but got " + typeOf(entry.getValue()));
                        continue;
                    }
                    config.infoConfig = parseInfoConfig((Map<String, Object>) entry.getValue());
                    continue;
                }

                int slotNumber;
                if (key instanceof Integer) slotNumber = (Integer) key;
                else if (key instanceof String) slotNumber = Integer.parseInt((String) key);
                else continue;

                if (slotNumber < 1 || slotNumber > MAX_SLOTS) continue;

                if (!(entry.getValue() instanceof Map)) {
                    problems.accept("Skipping report GUI slot " + slotNumber + ": expected a mapping but got " + typeOf(entry.getValue()));
                    continue;
                }

                config.slots.put(slotNumber, parseSlotConfig(slotNumber, (Map<String, Object>) entry.getValue()));
            } catch (RuntimeException e) {
                problems.accept("Skipping invalid report GUI entry '" + key + "': " + e.getMessage());
            }
        }
        return config;
    }

    private static String typeOf(@Nullable Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    private static ReportSlotConfig parseSlotConfig(int slotNumber, Map<String, Object> data) {
        ReportSlotConfig config = new ReportSlotConfig();
        config.setSlotNumber(slotNumber);

        if (data.containsKey("enabled")) config.setEnabled(YamlValues.coerceBoolean(data.get("enabled"), config.isEnabled()));
        if (data.containsKey("item")) config.setItem(YamlValues.coerceString(data.get("item"), config.getItem()));
        if (data.containsKey("title")) config.setTitle(YamlValues.coerceString(data.get("title"), config.getTitle()));
        if (data.containsKey("chat-report")) config.setChatReport(YamlValues.coerceBoolean(data.get("chat-report"), config.isChatReport()));
        if (data.containsKey("replay-capture")) config.setReplayCapture(YamlValues.coerceBoolean(data.get("replay-capture"), config.isReplayCapture()));
        if (data.containsKey("description")) {
            Object desc = data.get("description");
            if (desc instanceof List) config.setDescription(new ArrayList<>((List<String>) desc));
        }

        return config;
    }

    @SuppressWarnings("unchecked")
    private static InfoConfig parseInfoConfig(Map<String, Object> data) {
        InfoConfig config = new InfoConfig();
        if (data.containsKey("item")) config.setItem(YamlValues.coerceString(data.get("item"), config.getItem()));
        if (data.containsKey("title")) config.setTitle(YamlValues.coerceString(data.get("title"), config.getTitle()));
        if (data.containsKey("description")) {
            Object desc = data.get("description");
            if (desc instanceof List) config.setDescription(new ArrayList<>((List<String>) desc));
        }
        return config;
    }

    public @Nullable ReportSlotConfig getSlot(int slotNumber) {
        return slots.get(slotNumber);
    }
}
