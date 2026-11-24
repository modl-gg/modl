package gg.modl.minecraft.core.imports.sql;

import gg.modl.minecraft.api.LibraryRecord;
import gg.modl.minecraft.core.Libraries;
import gg.modl.minecraft.core.util.Java8Collections;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@RequiredArgsConstructor
public enum JdbcDriver {
    HSQLDB("org.hsqldb.jdbc.JDBCDriver", Libraries.IMPORT_HSQLDB,
            nativeFolder -> Java8Collections.mapOf("hsqldb.reconfig_logging", "false")),
    H2_LEGACY("org.h2.Driver", Libraries.IMPORT_H2_LEGACY, nativeFolder -> Collections.<String, String>emptyMap()),
    SQLITE("org.sqlite.JDBC", Libraries.IMPORT_SQLITE,
            nativeFolder -> Java8Collections.mapOf("org.sqlite.tmpdir", nativeFolder.toString())),
    MARIADB("org.mariadb.jdbc.Driver", Libraries.IMPORT_MARIADB, nativeFolder -> Collections.<String, String>emptyMap()),
    POSTGRESQL("org.postgresql.Driver", Libraries.IMPORT_POSTGRESQL, nativeFolder -> Collections.<String, String>emptyMap());

    @Getter private final String className;
    @Getter private final LibraryRecord library;
    private final Function<Path, Map<String, String>> loadingProperties;

    public Map<String, String> propertiesWhileLoading(Path nativeFolder) {
        return loadingProperties.apply(nativeFolder);
    }

    public static List<LibraryRecord> libraries() {
        List<LibraryRecord> records = new ArrayList<>();
        for (JdbcDriver driver : values()) records.add(driver.library);
        return Collections.unmodifiableList(records);
    }
}
