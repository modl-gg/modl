package gg.modl.backend.database.backup;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

public record TransferReport(SortedMap<String, Long> rowsByTable) {
    public TransferReport {
        rowsByTable = Collections.unmodifiableSortedMap(new TreeMap<>(rowsByTable));
    }

    public long tables() {
        return rowsByTable.size();
    }

    public long rows() {
        return rowsByTable.values().stream().mapToLong(Long::longValue).sum();
    }

    public List<RowCountMismatch> mismatchesWith(Map<String, Long> counted) {
        TreeSet<String> names = new TreeSet<>(rowsByTable.keySet());
        names.addAll(counted.keySet());
        return names.stream()
            .map(name -> new RowCountMismatch(name, rowsByTable.getOrDefault(name, 0L), counted.getOrDefault(name, 0L)))
            .filter(RowCountMismatch::differs)
            .toList();
    }

    public String describe() {
        return tables() + " tables, " + rows() + " rows";
    }
}
