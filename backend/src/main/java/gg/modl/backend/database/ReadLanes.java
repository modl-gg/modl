package gg.modl.backend.database;

public record ReadLanes(ReportLane reports, ReportLane searches) {
    public static ReadLanes of(DatabaseDialect dialect) {
        return new ReadLanes(new ReportLane(dialect.reportLanePermits(), ReportLane.DEFAULT_DEADLINE),
            new ReportLane(dialect.searchLanePermits(), ReportLane.SEARCH_DEADLINE));
    }

    public int permits() {
        return reports.permits() + searches.permits();
    }
}
