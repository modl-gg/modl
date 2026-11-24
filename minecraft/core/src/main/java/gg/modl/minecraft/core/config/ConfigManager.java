package gg.modl.minecraft.core.config;

import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.config.yaml.ConfigUpdater;
import gg.modl.minecraft.core.config.yaml.CoreManagedConfigs;
import gg.modl.minecraft.core.config.yaml.ManagedConfig;
import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static gg.modl.minecraft.core.util.Java8Collections.listOf;

public class ConfigManager implements GuiConfigSource {

    private final Path dataFolder;
    private final PluginLogger logger;

    private volatile LoadedConfigs loaded;

    @Value
    private static class LoadedConfigs {
        RuntimeConfigSource runtimeConfigSource;
        PunishGuiConfig punishGuiConfig;
        ReportGuiConfig reportGuiConfig;
        StandingGuiConfig standingGuiConfig;
        StaffChatConfig staffChatConfig;
        ChatManagementConfig chatManagementConfig;
        Staff2faConfig staff2faConfig;
        RealtimeConfig realtimeConfig;
        PanelLoginConfig panelLoginConfig;
        StaffModeSettings staffModeSettings;
        Map<Integer, String> punishmentTypeItems;
    }

    @Getter
    public static class StaffChatConfig {
        private boolean enabled = true;
        private String prefix = "!";
        private String format = "&b[Staff] &f{player}: &7{message}";

        public String formatMessage(String inGameName, @Nullable String panelName, String message) {
            return format
                .replace("{player}", sanitize(inGameName))
                .replace("{panel-name}", sanitize(panelName != null ? panelName : inGameName))
                .replace("{message}", sanitize(message));
        }

        private static String sanitize(@Nullable String input) {
            if (input == null) return "";
            return input.replace('\u00a7', '&').replaceAll("&([0-9a-fk-orA-FK-OR])", "&\u200b$1");
        }
    }

    @Getter
    public static class ChatManagementConfig {
        public static final int MAX_CLEAR_LINES = 500;

        private int clearLines = 100;

        public int linesToClear(@Nullable String requested) {
            if (requested == null || requested.trim().isEmpty()) return clearLines;
            try {
                int parsed = Integer.parseInt(requested.trim());
                return parsed >= 1 ? Math.min(parsed, MAX_CLEAR_LINES) : clearLines;
            } catch (NumberFormatException notANumber) {
                return clearLines;
            }
        }

        private void setClearLines(int configured) {
            clearLines = Math.max(1, Math.min(configured, MAX_CLEAR_LINES));
        }
    }

    @Getter
    public static class Staff2faConfig {
        private boolean enabled = false;
    }

    @Getter
    public static class RealtimeConfig {
        private boolean enabled = false;
    }

    @Getter
    public static class PanelLoginConfig {
        private boolean allowOfflineMode = false;
    }

    @Getter
    public static class StaffModeSettings {
        private boolean vanishOnEnable = true;
    }

    @Getter
    public static class StandingGuiConfig {
        private String socialItem = "minecraft:creeper_head";
        private String gameplayItem = "minecraft:tnt";
    }
    public ConfigManager(Path dataFolder, PluginLogger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        migrateGuiConfigsFromConfigYml();
        createDefaultGuiConfigFiles();
        this.loaded = load();
    }

    public void reloadAll() {
        loaded = load();
    }

    public RuntimeConfigSource getRuntimeConfigSource() {
        return loaded.getRuntimeConfigSource();
    }

    @Override
    public PunishGuiConfig getPunishGuiConfig() {
        return loaded.getPunishGuiConfig();
    }

    @Override
    public ReportGuiConfig getReportGuiConfig() {
        return loaded.getReportGuiConfig();
    }

    public StandingGuiConfig getStandingGuiConfig() {
        return loaded.getStandingGuiConfig();
    }

    public StaffChatConfig getStaffChatConfig() {
        return loaded.getStaffChatConfig();
    }

    public ChatManagementConfig getChatManagementConfig() {
        return loaded.getChatManagementConfig();
    }

    public Staff2faConfig getStaff2faConfig() {
        return loaded.getStaff2faConfig();
    }

    public RealtimeConfig getRealtimeConfig() {
        return loaded.getRealtimeConfig();
    }

    public PanelLoginConfig getPanelLoginConfig() {
        return loaded.getPanelLoginConfig();
    }

    public StaffModeSettings getStaffModeSettings() {
        return loaded.getStaffModeSettings();
    }

    public Map<Integer, String> getPunishmentTypeItems() {
        return loaded.getPunishmentTypeItems();
    }

    private LoadedConfigs load() {
        RuntimeConfigSource source = RuntimeConfigSource.load(dataFolder, logger);
        return new LoadedConfigs(
                source,
                PunishGuiConfig.load(dataFolder, logger),
                ReportGuiConfig.load(dataFolder, logger),
                loadStandingGuiConfig(source),
                loadStaffChatConfig(source),
                loadChatManagementConfig(source),
                loadStaff2faConfig(source),
                loadRealtimeConfig(source),
                loadPanelLoginConfig(source),
                loadStaffModeSettings(source),
                loadPunishmentTypeItems(source));
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, String> loadPunishmentTypeItems(RuntimeConfigSource source) {
        Map<Integer, String> items = new HashMap<>(getDefaultPunishmentTypeItems());
        try {
            Map<?, ?> itemsMap = null;
            if (source.root().containsKey("punishment_type_items_by_ordinal")) {
                itemsMap = (Map<?, ?>) source.root().get("punishment_type_items_by_ordinal");
            }

            if (itemsMap != null) for (Map.Entry<?, ?> entry : itemsMap.entrySet()) {
                try {
                    int ordinal = entry.getKey() instanceof Integer ? (Integer) entry.getKey() : Integer.parseInt(entry.getKey().toString());
                    String itemId = entry.getValue().toString();
                    String[] parts = itemId.split(":");
                    if (parts.length > 2) itemId = parts[0] + ":" + parts[1];
                    if (isMalformedMaterialId(itemId)) {
                        logger.warning("Skipping malformed punishment type item id '" + entry.getValue() + "' for ordinal " + ordinal + "; keeping default.");
                        continue;
                    }
                    items.put(ordinal, itemId);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception e) { logger.warning("Failed to load punishment type items: " + e.getMessage()); }

        return items;
    }

    private static boolean isMalformedMaterialId(String itemId) {
        if (itemId == null || itemId.trim().isEmpty()) return true;
        for (String part : itemId.split(":", -1)) {
            if (part.trim().isEmpty()) return true;
        }
        return false;
    }

    private static StaffChatConfig loadStaffChatConfig(RuntimeConfigSource source) {
        StaffChatConfig config = new StaffChatConfig();
        Map<String, Object> data = source.section("staff_chat.yml", "staff_chat");
        if (data.isEmpty()) return config;

        Object enabled = data.get("enabled");
        if (enabled != null) config.enabled = Boolean.TRUE.equals(enabled);
        Object prefix = data.get("prefix");
        if (prefix != null) config.prefix = String.valueOf(prefix);
        Object format = data.get("format");
        if (format != null) config.format = String.valueOf(format);

        return config;
    }

    private static ChatManagementConfig loadChatManagementConfig(RuntimeConfigSource source) {
        ChatManagementConfig config = new ChatManagementConfig();
        Map<String, Object> data = source.section("chat_management.yml", "chat_management");
        if (data.isEmpty()) return config;

        if (data.containsKey("clear_lines")) {
            Object val = data.get("clear_lines");
            if (val instanceof Number) config.setClearLines(((Number) val).intValue());
            else if (val instanceof String) {
                try {
                    config.setClearLines(Integer.parseInt((String) val));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return config;
    }

    @SuppressWarnings("unchecked")
    private StandingGuiConfig loadStandingGuiConfig(RuntimeConfigSource source) {
        StandingGuiConfig config = new StandingGuiConfig();
        try {
            Map<String, Object> gui;
            if (source.root().containsKey("standing_gui")) {
                gui = (Map<String, Object>) source.root().get("standing_gui");
            } else {
                gui = source.dedicatedSection("standing_gui.yml", "standing_gui");
            }

            if (gui.isEmpty()) return config;

            if (gui.containsKey("social_status")) {
                Map<String, Object> social = (Map<String, Object>) gui.get("social_status");
                if (social != null && social.containsKey("item")) config.socialItem = String.valueOf(social.get("item"));
            }

            if (gui.containsKey("gameplay_status")) {
                Map<String, Object> gameplay = (Map<String, Object>) gui.get("gameplay_status");
                if (gameplay != null && gameplay.containsKey("item")) config.gameplayItem = String.valueOf(gameplay.get("item"));
            }
        } catch (Exception e) { logger.warning("Failed to load standing GUI config: " + e.getMessage()); }
        return config;
    }

    private static Staff2faConfig loadStaff2faConfig(RuntimeConfigSource source) {
        Staff2faConfig config = new Staff2faConfig();
        config.enabled = loadEnabledBoolean(source, "staff_2fa.yml", "staff_2fa", config.enabled);
        return config;
    }

    private static RealtimeConfig loadRealtimeConfig(RuntimeConfigSource source) {
        RealtimeConfig config = new RealtimeConfig();
        config.enabled = loadEnabledBoolean(source, "realtime.yml", "realtime", config.enabled);
        return config;
    }

    private static PanelLoginConfig loadPanelLoginConfig(RuntimeConfigSource source) {
        PanelLoginConfig config = new PanelLoginConfig();
        Object allowOfflineMode = source.rootSection("panel_login").get("allow_offline_mode");
        if (allowOfflineMode != null) config.allowOfflineMode = Boolean.TRUE.equals(allowOfflineMode);
        return config;
    }

    private static StaffModeSettings loadStaffModeSettings(RuntimeConfigSource source) {
        StaffModeSettings settings = new StaffModeSettings();
        Object vanishOnEnable = source.rootSection("staff_mode").get("vanish_on_enable");
        if (vanishOnEnable instanceof Boolean) settings.vanishOnEnable = (Boolean) vanishOnEnable;
        return settings;
    }

    private static boolean loadEnabledBoolean(RuntimeConfigSource source, String fileName, String sectionName, boolean defaultValue) {
        Map<String, Object> data = source.section(fileName, sectionName);
        if (data.isEmpty()) return defaultValue;

        if (data.containsKey("enabled")) return Boolean.TRUE.equals(data.get("enabled"));
        return defaultValue;
    }

    private static Map<Integer, String> getDefaultPunishmentTypeItems() {
        Map<Integer, String> items = new HashMap<>();
        items.put(0, "minecraft:leather_boots");
        items.put(1, "minecraft:paper");
        items.put(2, "minecraft:barrier");
        items.put(3, "minecraft:barrier");
        items.put(4, "minecraft:player_head");
        items.put(5, "minecraft:bedrock");
        items.put(6, "minecraft:feather");
        items.put(7, "minecraft:pufferfish");
        items.put(8, "minecraft:creeper_head");
        items.put(9, "minecraft:ink_sac");
        items.put(10, "minecraft:name_tag");
        items.put(11, "minecraft:armor_stand");
        items.put(12, "minecraft:lava_bucket");
        items.put(13, "minecraft:spider_eye");
        items.put(14, "minecraft:diamond_sword");
        items.put(15, "minecraft:gold_ingot");
        items.put(16, "minecraft:experience_bottle");
        items.put(17, "minecraft:barrier");
        return items;
    }

    private void createDefaultGuiConfigFiles() {
        for (ManagedConfig config : CoreManagedConfigs.guiConfigs()) {
            if (ConfigUpdater.update(config, dataFolder, logger).isCreated()) {
                logger.debug("Created default config file: " + config.getFileName());
            }
        }
    }

    private void migrateGuiConfigsFromConfigYml() {
        Path configFile = dataFolder.resolve("config.yml");
        if (!Files.exists(configFile)) return;

        try {
            List<String> lines = Files.readAllLines(configFile, StandardCharsets.UTF_8);

            migratePunishGuiSection(lines);
            migrateReportGuiSection(lines);
            List<String> sectionsToRemove = listOf(
                    "punish_gui", "report_gui",
                    "staff_members_menu"
            );
            List<String> cleaned = removeTopLevelSections(lines, sectionsToRemove);
            if (cleaned.size() < lines.size()) {
                Files.write(configFile, (String.join("\n", cleaned) + "\n").getBytes(StandardCharsets.UTF_8));
                logger.info("Removed migrated GUI sections from config.yml");
            }
        } catch (Exception e) { logger.warning("Failed to migrate GUI configs from config.yml: " + e.getMessage()); }
    }

    private void migratePunishGuiSection(List<String> lines) throws IOException {
        migrateGuiSection(
                lines,
                "punish_gui.yml",
                "punish_gui",
                "Migrated punish GUI config from config.yml to punish_gui.yml"
        );
    }

    private void migrateReportGuiSection(List<String> lines) throws IOException {
        migrateGuiSection(
                lines,
                "report_gui.yml",
                "report_gui",
                "Migrated report_gui from config.yml to report_gui.yml"
        );
    }

    private void migrateGuiSection(
            List<String> lines,
            String fileName,
            String sectionName,
            String successMessage
    ) throws IOException {
        Path targetFile = dataFolder.resolve(fileName);
        if (Files.exists(targetFile)) return;

        String content = extractAndUnindentSection(lines, sectionName);
        if (content == null) return;

        Files.write(targetFile, (content.replaceAll("\\s+$", "") + "\n").getBytes(StandardCharsets.UTF_8));
        logger.info(successMessage);
    }

    static @Nullable String extractAndUnindentSection(List<String> lines, String key) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++)
            if (isTopLevelKey(lines.get(i), key)) {
                start = i + 1;
                break;
            }

        if (start == -1) return null;

        List<String> content = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.trim().isEmpty() && !Character.isWhitespace(line.charAt(0))) break;
            content.add(line);
        }

        while (!content.isEmpty() && content.get(content.size() - 1).trim().isEmpty()) {
            content.remove(content.size() - 1);
        }

        if (content.isEmpty()) return null;

        int minIndent = Integer.MAX_VALUE;
        for (String line : content) {
            if (line.trim().isEmpty()) continue;
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            minIndent = Math.min(minIndent, indent);
        }
        if (minIndent == Integer.MAX_VALUE) minIndent = 0;

        StringBuilder sb = new StringBuilder();
        for (String line : content) {
            if (line.trim().isEmpty()) sb.append("\n");
            else sb.append(line.substring(Math.min(minIndent, line.length()))).append("\n");
        }
        return sb.toString().replaceAll("\\s+$", "");
    }

    static List<String> removeTopLevelSections(List<String> lines, List<String> sectionKeys) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            boolean matched = false;
            for (String key : sectionKeys)
                if (isTopLevelKey(lines.get(i), key)) {
                    matched = true;
                    break;
                }

            if (matched) {
                i++;
                while (i < lines.size()) {
                    String line = lines.get(i);
                    if (!line.trim().isEmpty() && !Character.isWhitespace(line.charAt(0))) break;

                    i++;
                }

                while (!result.isEmpty() && result.get(result.size() - 1).trim().isEmpty()) {
                    result.remove(result.size() - 1);
                }

            } else {
                result.add(lines.get(i));
                i++;
            }
        }

        while (!result.isEmpty() && result.get(result.size() - 1).trim().isEmpty())
            result.remove(result.size() - 1);

        return result;
    }

    private static boolean isTopLevelKey(String line, String key) {
        return line.startsWith(key + ":");
    }

}
