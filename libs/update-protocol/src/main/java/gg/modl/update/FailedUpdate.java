package gg.modl.update;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FailedUpdate {
    public static final int FORMAT = 1;
    public static final int MAX_REASON_LENGTH = 1000;

    private final ReleaseVersion from;
    private final ReleaseVersion to;
    private final Instant failedAt;
    private final @Nullable String reason;
    private final boolean restored;

    public FailedUpdate(ReleaseVersion from, ReleaseVersion to, Instant failedAt, @Nullable String reason, boolean restored) {
        this.from = from;
        this.to = to;
        this.failedAt = failedAt;
        this.reason = reason == null || reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
        this.restored = restored;
    }

    static FailedUpdate fromJson(JsonFields fields) throws InvalidJsonFieldException {
        if (fields.integer("format") != FORMAT) {
            throw new InvalidJsonFieldException("has an unsupported format");
        }
        return new FailedUpdate(fields.version("from"), fields.version("to"), fields.instant("failedAt"),
            fields.optionalText("reason"), fields.flag("restored"));
    }

    Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("format", FORMAT);
        json.put("from", from.toString());
        json.put("to", to.toString());
        json.put("failedAt", failedAt.toString());
        if (reason != null) {
            json.put("reason", reason);
        }
        json.put("restored", restored);
        return json;
    }

    public ReleaseVersion from() {
        return from;
    }

    public ReleaseVersion to() {
        return to;
    }

    public Instant failedAt() {
        return failedAt;
    }

    public @Nullable String reason() {
        return reason;
    }

    public boolean restored() {
        return restored;
    }
}
