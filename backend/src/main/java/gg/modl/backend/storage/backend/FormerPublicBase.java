package gg.modl.backend.storage.backend;

import java.util.Optional;
import java.util.function.Function;

record FormerPublicBase(String baseUrl) implements Function<String, Optional<String>> {
    @Override
    public Optional<String> apply(String url) {
        String prefix = baseUrl + "/";
        if (url == null || !url.startsWith(prefix) || url.length() == prefix.length()) {
            return Optional.empty();
        }
        String key = url.substring(prefix.length());
        return key.contains("?") || key.contains("#") ? Optional.empty() : Optional.of(key);
    }
}
