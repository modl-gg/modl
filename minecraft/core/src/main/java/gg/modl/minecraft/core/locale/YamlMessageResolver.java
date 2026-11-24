package gg.modl.minecraft.core.locale;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YamlMessageResolver {

    public interface Colorizer {
        String colorize(String message);
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    private final Colorizer colorizer;

    public YamlMessageResolver(Colorizer colorizer) {
        this.colorizer = colorizer;
    }

    @SuppressWarnings("unchecked")
    public @Nullable Object lookup(Map<String, Object> root, String path) {
        Object current = root;
        for (String key : path.split("\\.")) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(key);
        }
        return current;
    }

    public String applyPlaceholders(String template, @Nullable Map<String, String> placeholders) {
        if (placeholders == null) return template;
        String result = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    String substituteRawValues(String template, Map<String, String> rawValues) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder substituted = new StringBuilder(template.length());
        int copiedUpTo = 0;
        while (matcher.find()) {
            substituted.append(template, copiedUpTo, matcher.start())
                    .append(rawValues.getOrDefault(matcher.group(1), matcher.group()));
            copiedUpTo = matcher.end();
        }
        return substituted.append(template, copiedUpTo, template.length()).toString();
    }

    public String render(String template, Map<String, String> placeholders) {
        return colorizer.colorize(applyPlaceholders(template, placeholders));
    }
}
