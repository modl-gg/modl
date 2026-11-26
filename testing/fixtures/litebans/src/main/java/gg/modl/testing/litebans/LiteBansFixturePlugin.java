package gg.modl.testing.litebans;

import litebans.api.Database;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

public final class LiteBansFixturePlugin extends JavaPlugin {
    private static final String SCHEMA = "schema.sql";
    private static final String SEED = "seed.sql";
    private static final String DATABASE = "fixture.db";

    private @Nullable Connection connection;

    @Override
    public void onEnable() {
        try {
            Path folder = Files.createDirectories(getDataFolder().toPath());
            Class.forName("org.sqlite.JDBC");
            Connection opened = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve(DATABASE).toAbsolutePath());
            connection = opened;
            run(opened, bundledSchema());
            Path seed = folder.resolve(SEED);
            if (Files.exists(seed)) {
                run(opened, Files.newInputStream(seed));
                Files.delete(seed);
            }
            Database.setInstance(new FixtureDatabase(opened));
        } catch (IOException | SQLException | ClassNotFoundException failure) {
            getLogger().log(Level.SEVERE, "The LiteBans fixture could not start", failure);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        Database.setInstance(null);
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException failure) {
            getLogger().log(Level.WARNING, "The LiteBans fixture database could not be closed", failure);
        }
    }

    private InputStream bundledSchema() throws IOException {
        InputStream schema = getResource(SCHEMA);
        if (schema == null) throw new IOException("The fixture jar has no " + SCHEMA);
        return schema;
    }

    private static void run(Connection connection, InputStream script) throws IOException, SQLException {
        connection.setAutoCommit(false);
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(script, StandardCharsets.UTF_8));
             Statement statement = connection.createStatement()) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (!line.trim().isEmpty()) statement.executeUpdate(line);
            }
            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }
    }
}
