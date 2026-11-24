package gg.modl.backend.hostedimport.model;

import java.util.ArrayList;
import java.util.List;

public record IssueEntry(long count, List<String> samples) {
    public static final int MAX_SAMPLES = 5;
    private static final int MAX_SAMPLE_LENGTH = 128;

    public IssueEntry {
        samples = List.copyOf(samples);
    }

    public IssueEntry plus(IssueEntry other) {
        List<String> merged = new ArrayList<>(samples);
        for (String sample : other.samples) {
            if (merged.size() >= MAX_SAMPLES) {
                break;
            }
            if (!merged.contains(sample)) {
                merged.add(sample);
            }
        }
        return new IssueEntry(count + other.count, merged);
    }

    public static IssueEntry of(String sample) {
        return new IssueEntry(1, sample.isEmpty() ? List.of() : List.of(fit(sample)));
    }

    private static String fit(String sample) {
        return sample.codePointCount(0, sample.length()) <= MAX_SAMPLE_LENGTH
            ? sample
            : sample.substring(0, sample.offsetByCodePoints(0, MAX_SAMPLE_LENGTH));
    }
}
