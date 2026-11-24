package gg.modl.backend.hostedimport.model;

public record SubjectCount(long total, long imported, long changed, long skipped) {
    public static final SubjectCount EMPTY = new SubjectCount(0, 0, 0, 0);

    public SubjectCount plus(SubjectCount other) {
        return new SubjectCount(total + other.total, imported + other.imported, changed + other.changed, skipped + other.skipped);
    }
}
