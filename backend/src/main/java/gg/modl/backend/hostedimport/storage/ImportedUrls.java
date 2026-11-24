package gg.modl.backend.hostedimport.storage;

import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.archive.ArchiveManifest;
import gg.modl.backend.hostedimport.archive.BinaryValue;
import gg.modl.backend.hostedimport.archive.FoldedDates;
import gg.modl.backend.hostedimport.archive.ObjectIdValue;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.hostedimport.model.IssueLog;
import gg.modl.backend.infrastructure.validation.SafeUrls;
import gg.modl.backend.storage.ObjectStorage;
import org.jspecify.annotations.Nullable;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

public final class ImportedUrls {
    private static final String KEY_MEMBER = "key";
    private static final String HTTP = "http://";
    private static final String HTTPS = "https://";
    private static final int MAX_TRAILING_PUNCTUATION = 8;
    private static final int MAX_PREFIX_OCCURRENCES = 16;
    private static final String TRAILING_PUNCTUATION = ".,;:!?*_~)]}";
    private static final String TOKEN_BREAKS = "\"'<>`[]{}|\\";
    private static final List<String> PANEL_PATHS = List.of("/replay?id=", "/ticket/", "/appeal", "/knowledgebase", "/panel/");
    private static final Set<String> UNSAFE_SCHEMES = Set.of("javascript:", "vbscript:", "data:");

    private final ImportedKeyPlan keys;
    private final List<String> bases;
    private final String keyPrefix;
    private final List<String> panelOrigins;
    private final Function<String, String> publicUrl;
    private final IssueLog issues;

    public ImportedUrls(ArchiveManifest manifest, ImportedKeyPlan keys, ObjectStorage storage, IssueLog issues) {
        this(manifest, keys, storage::publicUrl, issues);
    }

    ImportedUrls(ArchiveManifest manifest, ImportedKeyPlan keys, Function<String, String> publicUrl, IssueLog issues) {
        this.keys = keys;
        this.bases = manifest.publicBaseUrls().stream()
            .sorted((first, second) -> Integer.compare(second.length(), first.length()))
            .toList();
        this.keyPrefix = manifest.keyPrefix();
        this.panelOrigins = panelOrigins(manifest.source());
        this.publicUrl = publicUrl;
        this.issues = issues;
    }

    public Optional<String> localKey(@Nullable String rawKey) {
        return rawKey == null ? Optional.empty() : keys.forOriginalKey(rawKey).map(ImportedKeyPlan.PlannedObject::localKey);
    }

    @Nullable
    public String safeUrl(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String rewritten = rewriteText(url.trim());
        if (!SafeUrls.isSafe(rewritten)) {
            issues.report(IssueCode.URL_UNSAFE, "");
            return null;
        }
        return rewritten;
    }

    public String rewriteText(String text) {
        int first = nextUrl(text, 0);
        if (first < 0) {
            return text;
        }
        if (first == 0) {
            Optional<String> wholeValue = resolved(text);
            if (wholeValue.isPresent()) {
                return wholeValue.get();
            }
        }
        StringBuilder out = new StringBuilder(text.length());
        int position = 0;
        while (position < text.length()) {
            int start = nextUrl(text, position);
            if (start < 0) {
                out.append(text, position, text.length());
                break;
            }
            out.append(text, position, start);
            int end = tokenEnd(text, start);
            out.append(rewriteToken(text.substring(start, end)));
            position = end;
        }
        return out.toString();
    }

    public Map<String, Object> rewriteMap(Map<String, @Nullable Object> value) {
        return rewriteMembers(value, false);
    }

    public Map<String, Object> rewriteUntrustedMap(Map<String, @Nullable Object> value) {
        return rewriteMembers(value, true);
    }

    @Nullable
    public Object rewriteTree(@Nullable Object value) {
        return walk(value, false);
    }

    @Nullable
    public Object rewriteUntrustedTree(@Nullable Object value) {
        return walk(value, true);
    }

    private Map<String, Object> rewriteMembers(Map<?, ?> map, boolean neutraliseUnsafe) {
        Map<String, Object> rewritten = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            String name = String.valueOf(key);
            Optional<String> storageKey = KEY_MEMBER.equals(name) && item instanceof String rawKey ? localKey(rawKey) : Optional.empty();
            rewritten.put(name, storageKey.isPresent() ? storageKey.get() : walk(item, neutraliseUnsafe));
        });
        return rewritten;
    }

    @Nullable
    private Object walk(@Nullable Object value, boolean neutraliseUnsafe) {
        if (value instanceof Map<?, ?> map) {
            return rewriteMembers(map, neutraliseUnsafe);
        }
        if (value instanceof List<?> list) {
            List<Object> rewritten = new ArrayList<>(list.size());
            list.forEach(item -> rewritten.add(walk(item, neutraliseUnsafe)));
            return rewritten;
        }
        if (value instanceof String text) {
            if (neutraliseUnsafe && hasUnsafeScheme(text)) {
                issues.report(IssueCode.URL_UNSAFE, "");
                return "";
            }
            return rewriteText(text);
        }
        return plain(value);
    }

    @Nullable
    public static Object plain(@Nullable Object value) {
        if (value instanceof ObjectIdValue objectId) {
            return objectId.hex();
        }
        if (value instanceof BinaryValue binary) {
            return binary.plainText();
        }
        if (value instanceof Double number && !Double.isFinite(number)) {
            return null;
        }
        if (value instanceof Instant instant) {
            return ArchiveDocument.instantOf(instant);
        }
        if (value instanceof FoldedDates folded) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("first", folded.earliest());
            summary.put("last", folded.latest());
            summary.put("count", folded.count());
            return summary;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> plain = new LinkedHashMap<>();
            map.forEach((key, item) -> plain.put(String.valueOf(key), plain(item)));
            return plain;
        }
        if (value instanceof List<?> list) {
            List<Object> plain = new ArrayList<>(list.size());
            list.forEach(item -> plain.add(plain(item)));
            return plain;
        }
        return value;
    }

    private static boolean hasUnsafeScheme(String text) {
        int colon = text.indexOf(':');
        if (colon < 0) {
            return false;
        }
        StringBuilder scheme = new StringBuilder();
        for (int i = 0; i <= colon; i++) {
            char c = text.charAt(i);
            if (c > ' ') {
                scheme.append(c);
            }
        }
        return UNSAFE_SCHEMES.contains(scheme.toString().toLowerCase(Locale.ROOT));
    }

    private String rewriteToken(String token) {
        int end = token.length();
        for (int trimmed = 0; trimmed <= MAX_TRAILING_PUNCTUATION && end > 0; trimmed++) {
            Optional<String> local = resolved(token.substring(0, end));
            if (local.isPresent()) {
                return local.get() + token.substring(end);
            }
            if (TRAILING_PUNCTUATION.indexOf(token.charAt(end - 1)) < 0) {
                break;
            }
            end--;
        }
        for (String origin : panelOrigins) {
            if (token.regionMatches(true, 0, origin, 0, origin.length())) {
                String path = token.substring(origin.length());
                if (PANEL_PATHS.stream().anyMatch(path::startsWith)) {
                    return path;
                }
            }
        }
        if (bases.stream().anyMatch(token::startsWith)) {
            issues.report(IssueCode.URL_LEFT_REMOTE, "");
        }
        return token;
    }

    private Optional<String> resolved(String url) {
        for (String base : bases) {
            if (url.startsWith(base)) {
                Optional<String> exported = exactKey(url.substring(base.length()));
                if (exported.isPresent()) {
                    return exported.map(publicUrl);
                }
            }
        }
        return exportedPathOnOtherHost(url).map(publicUrl);
    }

    private Optional<String> exportedPathOnOtherHost(String url) {
        int authority = url.indexOf("://");
        int pathStart = authority < 0 ? -1 : url.indexOf('/', authority + 3);
        if (pathStart < 0) {
            return Optional.empty();
        }
        String path = withoutQuery(url.substring(pathStart));
        String marker = "/" + keyPrefix;
        int at = path.indexOf(marker);
        for (int seen = 0; at >= 0 && seen < MAX_PREFIX_OCCURRENCES; seen++) {
            Optional<String> exported = exactKey(path.substring(at + 1));
            if (exported.isPresent()) {
                return exported;
            }
            at = path.indexOf(marker, at + 1);
        }
        return Optional.empty();
    }

    private Optional<String> exactKey(String remainder) {
        String path = withoutQuery(remainder);
        return localKey(remainder).or(() -> localKey(path)).or(() -> localKey(decoded(path)));
    }

    private static String withoutQuery(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '?' || value.charAt(i) == '#') {
                return value.substring(0, i);
            }
        }
        return value;
    }

    private static String decoded(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static int nextUrl(String text, int from) {
        int at = text.indexOf("http", from);
        while (at >= 0) {
            if (text.startsWith(HTTP, at) || text.startsWith(HTTPS, at)) {
                return at;
            }
            at = text.indexOf("http", at + 1);
        }
        return -1;
    }

    private static int tokenEnd(String text, int start) {
        int openParentheses = 0;
        for (int end = start; end < text.length(); end++) {
            char c = text.charAt(end);
            if (Character.isWhitespace(c) || TOKEN_BREAKS.indexOf(c) >= 0) {
                return end;
            }
            if (c == '(') {
                openParentheses++;
            } else if (c == ')') {
                if (openParentheses == 0) {
                    return end;
                }
                openParentheses--;
            }
        }
        return text.length();
    }

    private static List<String> panelOrigins(ArchiveManifest.Source source) {
        return Stream.of(source.panelDomain(), source.defaultPanelDomain())
            .filter(Objects::nonNull)
            .filter(domain -> !domain.isBlank() && domain.indexOf('/') < 0)
            .map(domain -> HTTPS + domain.toLowerCase(Locale.ROOT))
            .distinct()
            .toList();
    }
}
