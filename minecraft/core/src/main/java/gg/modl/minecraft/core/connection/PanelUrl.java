package gg.modl.minecraft.core.connection;

import gg.modl.minecraft.core.util.UrlEncoding;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class PanelUrl {
    private final AtomicReference<@Nullable String> base;

    private PanelUrl(@Nullable String base) {
        this.base = new AtomicReference<>(base);
    }

    public static PanelUrl of(@Nullable String url) {
        return new PanelUrl(normalize(url));
    }

    public static PanelUrl unresolved() {
        return new PanelUrl(null);
    }

    public static boolean isValid(@Nullable String url) {
        try {
            normalize(url);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public Optional<String> known() {
        return Optional.ofNullable(base.get());
    }

    public String get() {
        String resolved = base.get();
        if (resolved == null) throw new IllegalStateException("The panel URL is not known until modl connects to its server");
        return resolved;
    }

    public synchronized boolean update(@Nullable String candidate) {
        String normalized;
        try {
            normalized = normalize(candidate);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        return apply(normalized);
    }

    public synchronized void resolve(@Nullable String url) {
        apply(normalize(url));
    }

    private boolean apply(String normalized) {
        return !normalized.equals(base.getAndSet(normalized));
    }

    public String staffTicket(String ticketId) {
        return link("/panel/tickets/" + encode(ticketId));
    }

    public String playerProfile(String minecraftUuid) {
        return link("/panel/player/" + encode(minecraftUuid));
    }

    public String appeal() {
        return link("/appeal");
    }

    public String replay(String replayId) {
        return link("/replay?id=" + encode(replayId));
    }

    public String evidenceUpload(String token) {
        return link("/upload-evidence/" + encode(token));
    }

    private String link(String path) {
        return get() + path;
    }

    private static String normalize(@Nullable String url) {
        HttpUrl parsed = HttpUrl.parseBase(url);
        return parsed.origin() + parsed.basePath();
    }

    private static String encode(@Nullable String value) {
        return UrlEncoding.encode(value == null ? "" : value);
    }
}
