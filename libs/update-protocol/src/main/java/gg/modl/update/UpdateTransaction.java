package gg.modl.update;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class UpdateTransaction {
    public static final int FORMAT = 1;
    public static final int MAX_ATTEMPTS = 3;

    private final String id;
    private final boolean managed;
    private final TransactionKind kind;
    private final ReleaseVersion from;
    private final @Nullable String fromArtifact;
    private final ReleaseVersion to;
    private final @Nullable String toArtifact;
    private final UpdatePhase phase;
    private final int attempts;
    private final @Nullable Boolean schemaChanged;
    private final @Nullable String databaseId;
    private final @Nullable String backup;
    private final @Nullable String backupSha256;
    private final Instant createdAt;
    private final @Nullable String failure;

    private UpdateTransaction(Builder builder) {
        this.id = builder.id;
        this.managed = builder.managed;
        this.kind = builder.kind;
        this.from = builder.from;
        this.fromArtifact = builder.fromArtifact;
        this.to = builder.to;
        this.toArtifact = builder.toArtifact;
        this.phase = builder.phase;
        this.attempts = builder.attempts;
        this.schemaChanged = builder.schemaChanged;
        this.databaseId = builder.databaseId;
        this.backup = builder.backup;
        this.backupSha256 = builder.backupSha256;
        this.createdAt = builder.createdAt;
        this.failure = builder.failure;
    }

    public static Builder builder(String id, TransactionKind kind, ReleaseVersion from, ReleaseVersion to, Instant createdAt) {
        return new Builder(id, kind, from, to, createdAt, UpdatePhase.STAGED);
    }

    public Builder toBuilder() {
        Builder builder = new Builder(id, kind, from, to, createdAt, phase);
        builder.managed = managed;
        builder.fromArtifact = fromArtifact;
        builder.toArtifact = toArtifact;
        builder.attempts = attempts;
        builder.schemaChanged = schemaChanged;
        builder.databaseId = databaseId;
        builder.backup = backup;
        builder.backupSha256 = backupSha256;
        builder.failure = failure;
        return builder;
    }

    public UpdateTransaction inPhase(UpdatePhase next) {
        return toBuilder().phase(next).build();
    }

    public UpdateTransaction rollingBack() {
        Builder next = toBuilder().phase(UpdatePhase.ROLLBACK);
        return phase.beforeMigration() ? next.schemaChanged(false).build() : next.build();
    }

    public UpdateTransaction timedOut(String reason) {
        return toBuilder().attempts(Math.max(attempts, MAX_ATTEMPTS)).failure(reason).build();
    }

    public boolean rollbackAllowed() {
        return phase.beforeMigration() || backup != null || Boolean.FALSE.equals(schemaChanged);
    }

    public boolean exhaustedAttempts() {
        return attempts >= MAX_ATTEMPTS;
    }

    static UpdateTransaction fromJson(JsonFields fields) throws InvalidJsonFieldException {
        if (fields.integer("format") != FORMAT) {
            throw new InvalidJsonFieldException("has an unsupported format");
        }
        Builder builder = builder(fields.text("id"), fields.constant("kind", TransactionKind.class), fields.version("from"),
            fields.version("to"), fields.instant("createdAt"));
        builder.managed = fields.flag("managed");
        builder.fromArtifact = fields.optionalText("fromArtifact");
        builder.toArtifact = fields.optionalText("toArtifact");
        builder.phase = fields.constant("phase", UpdatePhase.class);
        builder.attempts = fields.integer("attempts");
        builder.schemaChanged = fields.optionalFlag("schemaChanged");
        builder.databaseId = fields.optionalText("databaseId");
        builder.backup = fields.optionalText("backup");
        builder.backupSha256 = fields.optionalText("backupSha256");
        builder.failure = fields.optionalText("failure");
        if (builder.attempts < 0 || (builder.backup != null && !Hex.isSha256(builder.backupSha256))) {
            throw new InvalidJsonFieldException("is inconsistent");
        }
        return builder.build();
    }

    Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("format", FORMAT);
        json.put("id", id);
        json.put("managed", managed);
        json.put("kind", kind.name());
        json.put("from", from.toString());
        putIfPresent(json, "fromArtifact", fromArtifact);
        json.put("to", to.toString());
        putIfPresent(json, "toArtifact", toArtifact);
        json.put("phase", phase.name());
        json.put("attempts", attempts);
        putIfPresent(json, "schemaChanged", schemaChanged);
        putIfPresent(json, "databaseId", databaseId);
        putIfPresent(json, "backup", backup);
        putIfPresent(json, "backupSha256", backupSha256);
        json.put("createdAt", createdAt.toString());
        putIfPresent(json, "failure", failure);
        return json;
    }

    private static void putIfPresent(Map<String, Object> json, String name, @Nullable Object value) {
        if (value != null) {
            json.put(name, value);
        }
    }

    public String id() {
        return id;
    }

    public boolean managed() {
        return managed;
    }

    public TransactionKind kind() {
        return kind;
    }

    public ReleaseVersion from() {
        return from;
    }

    public @Nullable String fromArtifact() {
        return fromArtifact;
    }

    public ReleaseVersion to() {
        return to;
    }

    public @Nullable String toArtifact() {
        return toArtifact;
    }

    public UpdatePhase phase() {
        return phase;
    }

    public int attempts() {
        return attempts;
    }

    public @Nullable Boolean schemaChanged() {
        return schemaChanged;
    }

    public @Nullable String databaseId() {
        return databaseId;
    }

    public @Nullable String backup() {
        return backup;
    }

    public @Nullable String backupSha256() {
        return backupSha256;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public @Nullable String failure() {
        return failure;
    }

    public static final class Builder {
        private final String id;
        private final Instant createdAt;
        private boolean managed;
        private TransactionKind kind;
        private ReleaseVersion from;
        private @Nullable String fromArtifact;
        private ReleaseVersion to;
        private @Nullable String toArtifact;
        private UpdatePhase phase;
        private int attempts;
        private @Nullable Boolean schemaChanged;
        private @Nullable String databaseId;
        private @Nullable String backup;
        private @Nullable String backupSha256;
        private @Nullable String failure;

        private Builder(String id, TransactionKind kind, ReleaseVersion from, ReleaseVersion to, Instant createdAt, UpdatePhase phase) {
            this.id = id;
            this.kind = kind;
            this.from = from;
            this.to = to;
            this.createdAt = createdAt;
            this.phase = phase;
        }

        public Builder managed(boolean value) {
            this.managed = value;
            return this;
        }

        public Builder kind(TransactionKind value) {
            this.kind = value;
            return this;
        }

        public Builder from(ReleaseVersion value) {
            this.from = value;
            return this;
        }

        public Builder fromArtifact(@Nullable String value) {
            this.fromArtifact = value;
            return this;
        }

        public Builder toArtifact(@Nullable String value) {
            this.toArtifact = value;
            return this;
        }

        public Builder phase(UpdatePhase value) {
            this.phase = value;
            return this;
        }

        public Builder attempts(int value) {
            this.attempts = value;
            return this;
        }

        public Builder schemaChanged(@Nullable Boolean value) {
            this.schemaChanged = value;
            return this;
        }

        public Builder databaseId(@Nullable String value) {
            this.databaseId = value;
            return this;
        }

        public Builder backup(@Nullable String file, @Nullable String sha256) {
            this.backup = file;
            this.backupSha256 = sha256;
            return this;
        }

        public Builder failure(@Nullable String value) {
            this.failure = value;
            return this;
        }

        public UpdateTransaction build() {
            return new UpdateTransaction(this);
        }
    }
}
