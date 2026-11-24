package gg.modl.backend.hostedimport.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class IssueLog {
    private final Map<ImportSubject, SubjectCount> counts = new EnumMap<>(ImportSubject.class);
    private final Map<IssueCode, IssueEntry> issues = new EnumMap<>(IssueCode.class);

    public void report(IssueCode code, String sample) {
        issues.merge(code, IssueEntry.of(sample), IssueEntry::plus);
    }

    public void report(IssueCode code, long count) {
        if (count > 0) {
            issues.merge(code, new IssueEntry(count, List.of()), IssueEntry::plus);
        }
    }

    public void imported(ImportSubject subject) {
        counts.merge(subject, new SubjectCount(0, 1, 0, 0), SubjectCount::plus);
    }

    public void changed(ImportSubject subject) {
        counts.merge(subject, new SubjectCount(0, 0, 1, 0), SubjectCount::plus);
    }

    public void skipped(ImportSubject subject) {
        counts.merge(subject, new SubjectCount(0, 0, 0, 1), SubjectCount::plus);
    }

    public void seen(ImportSubject subject, long total) {
        counts.merge(subject, new SubjectCount(total, 0, 0, 0), SubjectCount::plus);
    }

    public ImportTally tally() {
        return new ImportTally(counts, issues);
    }

    public ImportTally drain() {
        ImportTally drained = tally();
        clear();
        return drained;
    }

    public void clear() {
        counts.clear();
        issues.clear();
    }
}
