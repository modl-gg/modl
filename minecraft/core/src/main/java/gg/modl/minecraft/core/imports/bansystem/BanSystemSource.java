package gg.modl.minecraft.core.imports.bansystem;

import gg.modl.minecraft.core.imports.source.ImportSource;
import gg.modl.minecraft.core.imports.source.SourceContext;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.imports.sql.DatabasePassword;
import gg.modl.minecraft.core.imports.sql.EmbeddedDatabase;
import gg.modl.minecraft.core.imports.sql.JdbcDriver;
import gg.modl.minecraft.core.imports.sql.RemoteDatabase;
import gg.modl.minecraft.core.imports.sql.SqlSession;
import gg.modl.minecraft.core.imports.support.ConfigFile;
import gg.modl.minecraft.core.imports.support.KnownNames;
import lombok.RequiredArgsConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public final class BanSystemSource implements ImportSource {
    private static final String PLUGIN = "BanSystem";
    private static final String DATABASE = "database";
    private static final String DATABASE_FILE = DATABASE + ".db";
    private static final String INTACT = "ok";

    private final SourceContext context;

    @Override
    public String id() {
        return "bansystem";
    }

    @Override
    public String displayName() {
        return PLUGIN;
    }

    @Override
    public boolean isPresent() {
        Path folder = context.pluginFolder(PLUGIN);
        return Files.isRegularFile(folder.resolve("config.yml")) || Files.isRegularFile(folder.resolve(DATABASE_FILE));
    }

    @Override
    public SourceReader open() throws SourceException {
        if (!isPresent()) throw SourceException.missing("No plugins/BanSystem folder");
        Path folder = context.pluginFolder(PLUGIN);
        ConfigFile config = ConfigFile.read(folder.resolve("config.yml"));
        boolean remote = config.bool("mysql.enable", false);
        SqlSession session = remote ? remote(config) : embedded(folder);
        return new BanSystemReader(session, context, KnownNames.fromServer(context.getServerRoot()), !remote);
    }

    private SqlSession remote(ConfigFile config) throws SourceException {
        RemoteDatabase database = RemoteDatabase.builder()
                .driver(JdbcDriver.MARIADB)
                .host(config.string("mysql.host", "localhost"))
                .port(config.integer("mysql.port", 3306))
                .database(config.string("mysql.database", "Bansystem"))
                .username(config.string("mysql.user", "root"))
                .password(DatabasePassword.of(config.string("mysql.password", "")))
                .build();
        return EmbeddedDatabase.openRemote(context.getConnections(), database);
    }

    private SqlSession embedded(Path folder) throws SourceException {
        if (!Files.isRegularFile(folder.resolve(DATABASE_FILE))) {
            throw SourceException.missing("No BanSystem database in " + folder);
        }
        return intact(EmbeddedDatabase.openCopy(context.getConnections(), context.getScratch(), folder.resolve(DATABASE),
                JdbcDriver.SQLITE, copy -> "jdbc:sqlite:" + copy + ".db", ""));
    }

    private static SqlSession intact(SqlSession copy) throws SourceException {
        List<String> problems = new ArrayList<>();
        try {
            copy.query("PRAGMA quick_check", row -> problems.add(row.getString(1)));
        } catch (SQLException failure) {
            copy.close();
            throw SourceException.failed(failure);
        }
        if (problems.size() == 1 && INTACT.equals(problems.get(0))) return copy;
        copy.close();
        throw SourceException.failed("The BanSystem database changed while it was copied, run the import again: " + problems);
    }
}
