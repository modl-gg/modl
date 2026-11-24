package gg.modl.backend.hostedimport.job;

import gg.modl.backend.hostedimport.model.ImportTally;

public record Positioned<T>(T item, long position, String sample, ImportTally reported) {
    Positioned<T> through(long laterPosition, ImportTally reportedSince) {
        return new Positioned<>(item, laterPosition, sample, reported.plus(reportedSince));
    }
}
