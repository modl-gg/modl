package gg.modl.backend.audit.repository;

import java.util.List;
import java.util.Map;

public interface AdminTableBrowserRepository {
    List<Map<String, Object>> readPage(BrowsableTable table, int limit, int offset);

    long count(BrowsableTable table);
}
