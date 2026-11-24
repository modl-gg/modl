package gg.modl.minecraft.core.imports.libertybans;

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
import gg.modl.minecraft.core.imports.support.PluginApi.PluginApiException;
import gg.modl.minecraft.core.imports.support.PluginApi;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;

@RequiredArgsConstructor
public final class LibertyBansSource implements ImportSource {
    private static final String PLUGIN = "LibertyBans";
    private static final String[] DATABASES = {"internal/hypersql/punishments-database", "hypersql/punishments-database"};
    private static final String DEFAULT_USER = "defaultuser";
    private static final String DEFAULT_PASSWORD = "defaultpass";

    private final SourceContext context;

    @Override
    public String id() {
        return "libertybans";
    }

    @Override
    public String displayName() {
        return PLUGIN;
    }

    @Override
    public boolean isPresent() {
        Path folder = context.pluginFolder(PLUGIN);
        return Files.isRegularFile(folder.resolve("sql.yml")) || embeddedDatabase(folder) != null;
    }

    @Override
    public SourceReader open() throws SourceException {
        if (!isPresent()) throw SourceException.missing("No plugins/LibertyBans folder");
        Path folder = context.pluginFolder(PLUGIN);
        ConfigFile sql = ConfigFile.read(folder.resolve("sql.yml"));
        String vendor = sql.string("rdms-vendor", "HSQLDB").toUpperCase(Locale.ROOT);
        String user = sql.string("auth-details.user", DEFAULT_USER);
        String password = sql.string("auth-details.password", DEFAULT_PASSWORD);
        if (context.getPluginEnabled().test(PLUGIN)) return new LibertyBansReader(running(), context);
        boolean embedded = "HSQLDB".equals(vendor) || DEFAULT_USER.equals(user) || DEFAULT_PASSWORD.equals(password);
        SqlSession session = embedded ? embedded(folder) : remote(sql, vendor, user, password);
        return new LibertyBansReader(session, context);
    }

    private SqlSession remote(ConfigFile sql, String vendor, String user, String password) throws SourceException {
        boolean postgres = vendor.startsWith("POSTGRES") || vendor.startsWith("COCKROACH");
        RemoteDatabase database = RemoteDatabase.builder()
                .driver(postgres ? JdbcDriver.POSTGRESQL : JdbcDriver.MARIADB)
                .host(sql.string("auth-details.host", "localhost"))
                .port(sql.integer("auth-details.port", postgres ? 5432 : 3306))
                .database(sql.string("auth-details.database", "bans"))
                .username(user)
                .password(DatabasePassword.of(password))
                .properties(sql.strings(postgres ? "postgres.connection-properties" : "mariadb.connection-properties"))
                .build();
        return EmbeddedDatabase.openRemote(context.getConnections(), database);
    }

    private SqlSession embedded(Path folder) throws SourceException {
        Path database = embeddedDatabase(folder);
        if (database == null) throw SourceException.missing("No LibertyBans database in " + folder);
        return EmbeddedDatabase.openCopy(context.getConnections(), context.getScratch(), database, JdbcDriver.HSQLDB,
                copy -> "jdbc:hsqldb:file:" + copy + ";hsqldb.lock_file=false;shutdown=true;hsqldb.applog=0;hsqldb.sqllog=0", "SA");
    }

    private SqlSession running() throws SourceException {
        try {
            PluginApi api = new PluginApi(LibertyBansSource.class.getClassLoader());
            Object omnibus = api.call("space.arim.omnibus.OmnibusProvider", "getOmnibus", null);
            Object registry = api.call("space.arim.omnibus.Omnibus", "getRegistry", omnibus);
            Class<?> libertyBans = api.type("space.arim.libertybans.api.LibertyBans");
            Optional<?> provider = (Optional<?>) api.call("space.arim.omnibus.registry.Registry", "getProvider", registry,
                    new Class<?>[]{Class.class}, libertyBans);
            if (provider == null || !provider.isPresent()) throw new PluginApiException("LibertyBans has not started");
            Object database = api.call("space.arim.libertybans.api.LibertyBans", "getDatabase", provider.get());
            Object connection = api.call("space.arim.libertybans.api.database.PunishmentDatabase", "getConnection", database);
            if (!(connection instanceof Connection)) throw new PluginApiException("LibertyBans gave no database connection");
            return autoCommitting((Connection) connection);
        } catch (PluginApiException | SQLException | RuntimeException failure) {
            throw SourceException.failed(failure);
        }
    }

    private static SqlSession autoCommitting(Connection connection) throws SQLException {
        try {
            connection.setAutoCommit(true);
            return SqlSession.owning(connection, () -> { });
        } catch (SQLException | RuntimeException failure) {
            connection.close();
            throw failure;
        }
    }

    private static @Nullable Path embeddedDatabase(Path folder) {
        for (String candidate : DATABASES) {
            Path database = folder.resolve(candidate);
            if (DatabaseCopy.exists(database, ".script") || DatabaseCopy.exists(database, ".properties")) return database;
        }
        return null;
    }
}
