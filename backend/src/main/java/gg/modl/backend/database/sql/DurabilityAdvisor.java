package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.SmartInitializingSingleton;

public final class DurabilityAdvisor implements SmartInitializingSingleton {
    private static final String ON = "on";

    private final DatabaseDialect dialect;
    private final DSLContext dsl;
    private final OperatorNotice notice;

    public DurabilityAdvisor(DatabaseDialect dialect, DSLContext dsl, OperatorNotice notice) {
        this.dialect = dialect;
        this.dsl = dsl;
        this.notice = notice;
    }

    @Override
    public void afterSingletonsInstantiated() {
        warnings().stream().map(DurabilityWarning::describe).forEach(notice::publish);
    }

    public List<DurabilityWarning> warnings() {
        return switch (dialect) {
            case SQLITE -> sqliteWarnings();
            case MYSQL, MARIADB -> mysqlWarnings();
            case POSTGRESQL -> postgresqlWarnings();
        };
    }

    private List<DurabilityWarning> sqliteWarnings() {
        List<DurabilityWarning> warnings = new ArrayList<>();
        String journalMode = value("PRAGMA journal_mode", String.class);
        if (!"wal".equalsIgnoreCase(journalMode)) {
            warnings.add(DurabilityWarning.journalMode(String.valueOf(journalMode)));
        }
        Integer synchronous = value("PRAGMA synchronous", Integer.class);
        if (!Objects.equals(synchronous, 2)) {
            warnings.add(DurabilityWarning.synchronous(String.valueOf(synchronous)));
        }
        return warnings;
    }

    private List<DurabilityWarning> mysqlWarnings() {
        Record settings = dsl.fetchOne("SELECT @@GLOBAL.innodb_flush_log_at_trx_commit, @@GLOBAL.sync_binlog, @@GLOBAL.log_bin, @@SESSION.sql_mode");
        List<DurabilityWarning> warnings = new ArrayList<>();
        if (settings == null) {
            return warnings;
        }
        if (!Objects.equals(String.valueOf(settings.get(0)), "1")) {
            warnings.add(settingWarning("innodb_flush_log_at_trx_commit", settings.get(0), "1"));
        }
        if (isEnabled(settings.get(2)) && !Objects.equals(String.valueOf(settings.get(1)), "1")) {
            warnings.add(settingWarning("sync_binlog", settings.get(1), "1"));
        }
        if (!String.valueOf(settings.get(3)).toUpperCase(Locale.ROOT).contains("STRICT_ALL_TABLES")) {
            warnings.add(DurabilityWarning.notStrict());
        }
        return warnings;
    }

    private List<DurabilityWarning> postgresqlWarnings() {
        List<DurabilityWarning> warnings = new ArrayList<>();
        for (String setting : List.of("fsync", "synchronous_commit", "full_page_writes")) {
            String value = value("SHOW " + setting, String.class);
            if (!ON.equalsIgnoreCase(value)) {
                warnings.add(settingWarning(setting, value, ON));
            }
        }
        return warnings;
    }

    private <T> @Nullable T value(String sql, Class<T> type) {
        Record row = dsl.fetchOne(sql);
        return row == null ? null : row.get(0, type);
    }

    private static boolean isEnabled(@Nullable Object value) {
        String text = String.valueOf(value);
        return "1".equals(text) || ON.equalsIgnoreCase(text) || "true".equalsIgnoreCase(text);
    }

    private static DurabilityWarning settingWarning(String setting, @Nullable Object value, String expected) {
        return DurabilityWarning.setting(setting, String.valueOf(value), expected);
    }
}
