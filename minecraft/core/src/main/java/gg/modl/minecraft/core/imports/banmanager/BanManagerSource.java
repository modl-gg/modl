package gg.modl.minecraft.core.imports.banmanager;

import gg.modl.minecraft.core.imports.source.ImportSource;
import gg.modl.minecraft.core.imports.source.SourceContext;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.imports.sql.DatabasePassword;
import gg.modl.minecraft.core.imports.sql.EmbeddedDatabase;
import gg.modl.minecraft.core.imports.sql.JdbcDriver;
import gg.modl.minecraft.core.imports.sql.RemoteDatabase;
import gg.modl.minecraft.core.imports.sql.SqlSession;
import gg.modl.minecraft.core.imports.sql.TransportSecurity;
import gg.modl.minecraft.core.imports.support.ConfigFile;
import gg.modl.minecraft.core.imports.support.PlayerKeys;
import gg.modl.minecraft.core.imports.support.PluginApi.PluginApiException;
import gg.modl.minecraft.core.imports.support.PluginApi;
import lombok.RequiredArgsConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@RequiredArgsConstructor
public final class BanManagerSource implements ImportSource {
    private static final String PLUGIN = "BanManager";
    private static final String LOCAL = "databases.local.";
    private static final String DEFAULT_NAME = "local_bans";
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String CONNECTION_SOURCE = "me.confuser.banmanager.common.ormlite.support.ConnectionSource";
    private static final String JDBC_CONNECTION = "me.confuser.banmanager.common.ormlite.jdbc.JdbcDatabaseConnection";

    private final SourceContext context;

    @Override
    public String id() {
        return "banmanager";
    }

    @Override
    public String displayName() {
        return PLUGIN;
    }

    @Override
    public boolean isPresent() {
        return Files.isRegularFile(context.pluginFolder(PLUGIN).resolve("config.yml"));
    }

    @Override
    public SourceReader open() throws SourceException {
        if (!isPresent()) throw SourceException.missing("No plugins/BanManager/config.yml");
        Path folder = context.pluginFolder(PLUGIN);
        ConfigFile config = ConfigFile.read(folder.resolve("config.yml"));
        String console = PlayerKeys.uuidOrNull(ConfigFile.read(folder.resolve("console.yml")).string("uuid", ""));
        String storage = config.string(LOCAL + "storageType", "mysql").toLowerCase(Locale.ROOT);
        SqlSession session = context.getPluginEnabled().test(PLUGIN) ? running()
                : "h2".equals(storage) ? embedded(folder, config) : remote(config);
        return new BanManagerReader(session, context, BanManagerTables.of(config), console);
    }

    private SqlSession remote(ConfigFile config) throws SourceException {
        RemoteDatabase database = RemoteDatabase.builder()
                .driver(JdbcDriver.MARIADB)
                .host(config.string(LOCAL + "host", "127.0.0.1"))
                .port(config.integer(LOCAL + "port", 3306))
                .database(config.string(LOCAL + "name", "local_bans"))
                .username(config.string(LOCAL + "user", "root"))
                .password(DatabasePassword.of(config.string(LOCAL + "password", "")))
                .properties(connectionProperties(config))
                .build();
        return EmbeddedDatabase.openRemote(context.getConnections(), database);
    }

    private static Map<String, String> connectionProperties(ConfigFile config) {
        Map<String, String> properties = new LinkedHashMap<>(TransportSecurity.of(config.bool(LOCAL + "useSSL", false),
                config.bool(LOCAL + "verifyServerCertificate", false)).asMariaDbProperty());
        properties.put("allowPublicKeyRetrieval", String.valueOf(config.bool(LOCAL + "allowPublicKeyRetrieval", false)));
        return properties;
    }

    private SqlSession embedded(Path folder, ConfigFile config) throws SourceException {
        String name = config.string(LOCAL + "name", DEFAULT_NAME).trim();
        Path database = folder.resolve(FILE_NAME.matcher(name).matches() ? name : DEFAULT_NAME);
        if (!Files.isRegularFile(database.resolveSibling(database.getFileName() + ".mv.db"))) {
            throw SourceException.missing("No BanManager database in " + folder);
        }
        return EmbeddedDatabase.openCopy(context.getConnections(), context.getScratch(), database, JdbcDriver.H2_LEGACY,
                copy -> "jdbc:h2:file:" + copy + ";MODE=MySQL;IGNORECASE=TRUE;FILE_LOCK=NO;IFEXISTS=TRUE", "");
    }

    private SqlSession running() throws SourceException {
        try {
            PluginApi api = new PluginApi(BanManagerSource.class.getClassLoader());
            Object source = api.call("me.confuser.banmanager.common.api.BmAPI", "getLocalConnection", null);
            if (source == null) throw new PluginApiException("BanManager has not started");
            Object borrowed = api.call(CONNECTION_SOURCE, "getReadOnlyConnection", source,
                    new Class<?>[]{String.class}, "");
            if (borrowed == null) throw new PluginApiException("BanManager gave no database connection");
            Class<?> databaseConnection = api.type("me.confuser.banmanager.common.ormlite.support.DatabaseConnection");
            SqlSession.Release release = () -> release(api, source, databaseConnection, borrowed);
            Object connection = api.call(JDBC_CONNECTION, "getInternalConnection", borrowed);
            if (!(connection instanceof Connection)) {
                release.release();
                throw new PluginApiException("BanManager gave no database connection");
            }
            return SqlSession.borrowing((Connection) connection, release);
        } catch (PluginApiException | SQLException | RuntimeException failure) {
            throw SourceException.failed(failure);
        }
    }

    private static void release(PluginApi api, Object source, Class<?> databaseConnection, Object borrowed) throws SQLException {
        try {
            api.call(CONNECTION_SOURCE, "releaseConnection", source, new Class<?>[]{databaseConnection}, borrowed);
        } catch (PluginApiException failed) {
            throw new SQLException("BanManager did not take its database connection back: " + failed.getMessage(), failed);
        }
    }
}
