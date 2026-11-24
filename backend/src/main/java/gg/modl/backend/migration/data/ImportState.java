package gg.modl.backend.migration.data;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum ImportState {
    BUILDING("building_json", Duration.ofMinutes(5)),
    UPLOADING("uploading_json", Duration.ofMinutes(5)),
    RECEIVING("receiving_file", Duration.ofMinutes(5)),
    PROCESSING("processing_data", Duration.ofMinutes(30)),
    COMPLETED("completed", Duration.ZERO),
    FAILED("failed", Duration.ZERO);

    public static final Set<ImportState> BEFORE_ACCEPTANCE =
        Collections.unmodifiableSet(EnumSet.of(BUILDING, UPLOADING, RECEIVING));
    public static final Set<ImportState> ACTIVE =
        Collections.unmodifiableSet(EnumSet.of(BUILDING, UPLOADING, RECEIVING, PROCESSING));

    private final String storedValue;
    private final Duration lease;

    ImportState(String storedValue, Duration lease) {
        this.storedValue = storedValue;
        this.lease = lease;
    }

    public static ImportState fromStored(String storedValue) {
        return Arrays.stream(values())
            .filter(state -> state.storedValue.equals(storedValue))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Unknown import job status " + storedValue));
    }

    public String storedValue() {
        return storedValue;
    }

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public Instant staleBefore(Instant now) {
        return now.minus(lease);
    }
}
