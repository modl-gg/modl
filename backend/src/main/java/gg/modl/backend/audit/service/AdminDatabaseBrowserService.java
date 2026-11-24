package gg.modl.backend.audit.service;

import gg.modl.backend.audit.dto.response.DatabaseTablePage;
import gg.modl.backend.audit.repository.AdminTableBrowserRepository;
import gg.modl.backend.audit.repository.BrowsableTable;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.server.data.Server;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AdminDatabaseBrowserService {
    private static final Map<String, BrowsableTable> TABLES_BY_IDENTIFIER = Map.of(
        "players", BrowsableTable.PLAYERS,
        "settings", BrowsableTable.SETTINGS,
        "staffs", BrowsableTable.STAFF,
        "staffroles", BrowsableTable.STAFF_ROLES,
        "tickets", BrowsableTable.TICKETS,
        "ticket_verifications", BrowsableTable.TICKET_VERIFICATIONS,
        "logs", BrowsableTable.AUDIT_LOGS,
        "knowledgebasecategories", BrowsableTable.KNOWLEDGEBASE_CATEGORIES,
        "knowledgebasearticles", BrowsableTable.KNOWLEDGEBASE_ARTICLES,
        "homepagecards", BrowsableTable.HOMEPAGE_CARDS);

    private final AdminTableBrowserRepository tableBrowserRepository;
    private final AdminRowRedactor redactor;

    public AdminDatabaseBrowserService(AdminTableBrowserRepository tableBrowserRepository, JsonColumns jsonColumns) {
        this.tableBrowserRepository = tableBrowserRepository;
        this.redactor = new AdminRowRedactor(jsonColumns);
    }

    public DatabaseTablePage getDatabaseTable(Server server, String identifier, int limit, int skip) {
        BrowsableTable table = TABLES_BY_IDENTIFIER.get(identifier);
        if (table == null) {
            throw new ValidationException("Invalid table name");
        }
        List<Map<String, Object>> rows = tableBrowserRepository.readPage(table, limit, skip).stream()
            .map(row -> redactor.redact(table, row))
            .toList();
        return new DatabaseTablePage(rows, tableBrowserRepository.count(table), limit, skip);
    }
}
