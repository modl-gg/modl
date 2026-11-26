package gg.modl.backend.hostedimport.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public record ImportTally(Map<ImportSubject, SubjectCount> counts, Map<IssueCode, IssueEntry> issues) {
    private static final Set<IssueCode> FOUND_ONLY_BY_ANALYSIS = Set.of(
        IssueCode.RECORD_UNREADABLE, IssueCode.RECORD_TOO_LARGE, IssueCode.PLAYER_MERGED, IssueCode.STAT_WIPE_PENDING,
        IssueCode.COLLECTION_UNKNOWN, IssueCode.FILE_SKIPPED_BY_EXPORT,
        IssueCode.FILE_KEY_RENAMED, IssueCode.FILE_CASE_CONFLICT, IssueCode.FILE_TYPE_NEUTRALISED);
    public static final ImportTally EMPTY = new ImportTally(Map.of(), Map.of());

    public ImportTally {
        counts = Map.copyOf(counts);
        issues = Map.copyOf(issues);
    }

    @JsonCreator
    static ImportTally stored(@JsonProperty("counts") @Nullable Map<ImportSubject, SubjectCount> counts,
                              @JsonProperty("issues") @Nullable Map<String, IssueEntry> issues) {
        Map<IssueCode, IssueEntry> known = new EnumMap<>(IssueCode.class);
        Objects.requireNonNullElse(issues, Map.<String, IssueEntry>of())
            .forEach((name, entry) -> IssueCode.named(name).ifPresent(code -> known.put(code, entry)));
        return new ImportTally(Objects.requireNonNullElse(counts, Map.of()), known);
    }

    public ImportTally plus(ImportTally other) {
        Map<ImportSubject, SubjectCount> mergedCounts = new EnumMap<>(ImportSubject.class);
        mergedCounts.putAll(counts);
        other.counts.forEach((subject, count) -> mergedCounts.merge(subject, count, SubjectCount::plus));
        Map<IssueCode, IssueEntry> mergedIssues = new EnumMap<>(IssueCode.class);
        mergedIssues.putAll(issues);
        other.issues.forEach((code, entry) -> mergedIssues.merge(code, entry, IssueEntry::plus));
        return new ImportTally(mergedCounts, mergedIssues);
    }

    public ImportTally carriedIntoApply() {
        Map<ImportSubject, SubjectCount> totals = new EnumMap<>(ImportSubject.class);
        counts.forEach((subject, count) -> totals.put(subject, new SubjectCount(count.total(), 0, 0, 0)));
        Map<IssueCode, IssueEntry> carried = new EnumMap<>(IssueCode.class);
        issues.forEach((code, entry) -> {
            if (FOUND_ONLY_BY_ANALYSIS.contains(code)) {
                carried.put(code, entry);
            }
        });
        return new ImportTally(totals, carried);
    }

    public static ImportTally rejected(ImportSubject subject, String sample) {
        return new ImportTally(Map.of(subject, new SubjectCount(0, -1, 0, 1)),
            Map.of(IssueCode.RECORD_REJECTED, IssueEntry.of(sample)));
    }

    public SubjectCount count(ImportSubject subject) {
        return counts.getOrDefault(subject, SubjectCount.EMPTY);
    }
}
