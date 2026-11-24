package gg.modl.minecraft.core.imports.advancedban;

import gg.modl.minecraft.core.imports.source.ImportSource;
import gg.modl.minecraft.core.imports.source.SourceContext;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.imports.sql.DatabaseCopy;
import gg.modl.minecraft.core.imports.sql.DatabasePassword;
import gg.modl.minecraft.core.imports.sql.EmbeddedDatabase;
import gg.modl.minecraft.core.imports.sql.JdbcDriver;
import gg.modl.minecraft.core.imports.sql.RemoteDatabase;
import gg.modl.minecraft.core.imports.sql.SqlSession;
import gg.modl.minecraft.core.imports.support.ConfigFile;
import gg.modl.minecraft.core.imports.support.KnownNames;
import gg.modl.minecraft.core.imports.support.SourceSupport;
import lombok.RequiredArgsConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
public final class AdvancedBanSource implements ImportSource {
    private static final String PLUGIN = "AdvancedBan";
    private static final String STORAGE = "data/storage";
    private static final String MYSQL_FILE = "MySQL.yml";
    private static final String HSQLDB_SETTINGS = ";hsqldb.lock_file=false;shutdown=true;hsqldb.applog=0;hsqldb.sqllog=0";
    private static final String DEFAULT_PROPERTIES = "useUnicode=true&characterEncoding=utf8";

    private final SourceContext context;

    @Override
    public String id() {
        return "advancedban";
    }

    @Override
    public String displayName() {
        return PLUGIN;
    }

    @Override
    public boolean isPresent() {
        Path folder = context.pluginFolder(PLUGIN);
        return Files.isRegularFile(folder.resolve("config.yml"))
                || DatabaseCopy.exists(folder.resolve(STORAGE), ".script");
    }

    @Override
    public SourceReader open() throws SourceException {
        if (!isPresent()) throw SourceException.missing("No plugins/AdvancedBan folder");
        Path folder = context.pluginFolder(PLUGIN);
        ConfigFile config = ConfigFile.read(folder.resolve("config.yml"));
        boolean remote = config.bool("UseMySQL", false);
        SqlSession session = remote ? remote(folder, config) : embedded(folder);
        return new AdvancedBanReader(session, context, KnownNames.fromServer(context.getServerRoot()), !remote);
    }

    private SqlSession remote(Path folder, ConfigFile config) throws SourceException {
        Path mysqlFile = folder.resolve(MYSQL_FILE);
        ConfigFile mysql = Files.isRegularFile(mysqlFile) ? ConfigFile.read(mysqlFile) : config;
        RemoteDatabase database = RemoteDatabase.builder()
                .driver(JdbcDriver.MARIADB)
                .host(mysql.string("MySQL.IP", "localhost"))
                .port(mysql.integer("MySQL.Port", 3306))
                .database(mysql.string("MySQL.DB-Name", "Bans"))
                .username(mysql.string("MySQL.Username", "root"))
                .password(DatabasePassword.of(mysql.string("MySQL.Password", "")))
                .properties(queryProperties(mysql.string("MySQL.Properties", DEFAULT_PROPERTIES)))
                .build();
        return EmbeddedDatabase.openRemote(context.getConnections(), database);
    }

    private SqlSession embedded(Path folder) throws SourceException {
        Path storage = folder.resolve(STORAGE);
        if (!DatabaseCopy.exists(storage, ".script")) throw SourceException.missing("No AdvancedBan database in " + storage);
        if (context.getPluginEnabled().test(PLUGIN)) throw SourceSupport.runningRefusal(PLUGIN);
        return EmbeddedDatabase.openCopy(context.getConnections(), context.getScratch(), storage, JdbcDriver.HSQLDB,
                copy -> "jdbc:hsqldb:file:" + copy + HSQLDB_SETTINGS, "SA");
    }

    static Map<String, String> queryProperties(String query) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0) properties.put(pair.substring(0, separator).trim(), pair.substring(separator + 1).trim());
        }
        return properties;
    }
}
