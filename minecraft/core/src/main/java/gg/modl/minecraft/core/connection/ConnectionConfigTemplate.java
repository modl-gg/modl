package gg.modl.minecraft.core.connection;

import gg.modl.minecraft.core.config.yaml.YamlDocument;

import java.util.Map;

final class ConnectionConfigTemplate {
    private ConnectionConfigTemplate() {
    }

    static String render(String template, Map<String, Object> values) {
        YamlDocument document = YamlDocument.parse(template);
        for (Map.Entry<String, Object> value : values.entrySet()) {
            if (!document.replaceScalar(value.getKey(), renderValue(value.getValue()))) {
                throw new IllegalArgumentException("Template has no key " + value.getKey());
            }
        }
        return document.render();
    }

    static String renderValue(Object value) {
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value instanceof ConnectionMode) return ((ConnectionMode) value).yamlName();
        String text = String.valueOf(value);
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
