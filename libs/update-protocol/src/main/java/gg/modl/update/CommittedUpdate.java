package gg.modl.update;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CommittedUpdate {
    public static final int FORMAT = 1;

    private final String transactionId;
    private final TransactionKind kind;
    private final ReleaseVersion version;
    private final @Nullable String artifact;
    private final Instant committedAt;

    public CommittedUpdate(String transactionId, TransactionKind kind, ReleaseVersion version, @Nullable String artifact, Instant committedAt) {
        this.transactionId = transactionId;
        this.kind = kind;
        this.version = version;
        this.artifact = artifact;
        this.committedAt = committedAt;
    }

    public static CommittedUpdate of(UpdateTransaction transaction, Instant committedAt) {
        return new CommittedUpdate(transaction.id(), transaction.kind(), transaction.to(), transaction.toArtifact(), committedAt);
    }

    public boolean commits(@Nullable UpdateTransaction transaction) {
        return transaction != null && transactionId.equals(transaction.id());
    }

    static CommittedUpdate fromJson(JsonFields fields) throws InvalidJsonFieldException {
        if (fields.integer("format") != FORMAT) {
            throw new InvalidJsonFieldException("has an unsupported format");
        }
        return new CommittedUpdate(fields.text("transactionId"), fields.constant("kind", TransactionKind.class),
            fields.version("version"), fields.optionalText("artifact"), fields.instant("committedAt"));
    }

    Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("format", FORMAT);
        json.put("transactionId", transactionId);
        json.put("kind", kind.name());
        json.put("version", version.toString());
        if (artifact != null) {
            json.put("artifact", artifact);
        }
        json.put("committedAt", committedAt.toString());
        return json;
    }

    public String transactionId() {
        return transactionId;
    }

    public TransactionKind kind() {
        return kind;
    }

    public ReleaseVersion version() {
        return version;
    }

    public @Nullable String artifact() {
        return artifact;
    }

    public Instant committedAt() {
        return committedAt;
    }
}
