package gg.modl.minecraft.core.imports.sql;

import gg.modl.minecraft.core.imports.source.SourceException;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Properties;
import java.util.function.Function;

public final class EmbeddedDatabase {
    private EmbeddedDatabase() {
    }

    public static SqlSession openCopy(JdbcConnections connections, Path scratch, Path original, JdbcDriver driver,
                                      Function<Path, String> url, String username) throws SourceException {
        DatabaseCopy copy;
        try {
            copy = DatabaseCopy.of(scratch, original);
        } catch (DatabaseCopy.NoSpaceException full) {
            throw SourceException.noSpace(String.valueOf(full.getMessage()));
        } catch (IOException failure) {
            throw SourceException.failed(failure);
        }
        try {
            Properties properties = new Properties();
            properties.setProperty("user", username);
            properties.setProperty("password", "");
            return connections.session(driver, url.apply(copy.base()), properties, connection -> { }, copy::close);
        } catch (SQLException | RuntimeException failure) {
            copy.close();
            throw SourceException.failed(failure);
        }
    }

    public static SqlSession openRemote(JdbcConnections connections, RemoteDatabase database) throws SourceException {
        try {
            return database.open(connections);
        } catch (SQLException | RuntimeException failure) {
            throw SourceException.failed(new SQLException("Could not connect to the " + database + ": " + failure.getMessage(),
                    failure));
        }
    }
}
