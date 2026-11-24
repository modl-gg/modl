package gg.modl.backend.database.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DatabaseConnectionTest {
    private static final SqlDatabaseProperties BASE = SqlDatabaseProperties.builtIn();

    @Test
    void theUrlIsBuiltFromCheckedPartsWithoutCredentials() {
        DatabaseConnection connection = new DatabaseConnection(DatabaseDialect.POSTGRESQL, " db.example.com ", 5432, "modl", true,
            "modl", "secret");

        SqlDatabaseProperties properties = connection.properties(BASE);

        assertThat(properties.externalUrl())
            .contains("jdbc:postgresql://db.example.com:5432/modl?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory");
        assertThat(properties.username()).isEqualTo("modl");
        assertThat(properties.password()).isEqualTo("secret");
        assertThat(connection.toString()).doesNotContain("secret");
    }

    @Test
    void eachDialectRequiresVerifiedTlsItsOwnWay() {
        assertThat(url(DatabaseDialect.MYSQL, "10.0.0.5", true)).isEqualTo("jdbc:mysql://10.0.0.5:3306/modl?sslMode=VERIFY_IDENTITY");
        assertThat(url(DatabaseDialect.MARIADB, "10.0.0.5", true)).isEqualTo("jdbc:mariadb://10.0.0.5:3306/modl?sslMode=verify-full");
        assertThat(url(DatabaseDialect.MYSQL, "::1", false)).isEqualTo("jdbc:mysql://[::1]:3306/modl");
        assertThat(url(DatabaseDialect.MYSQL, "modl_db", false)).isEqualTo("jdbc:mysql://modl_db:3306/modl");
    }

    @ParameterizedTest
    @ValueSource(strings = {"db.example.com/other", "db?allowLoadLocalInfile=true", "user@db", "db:3306", "-db", ""})
    void aHostThatCouldChangeTheUrlIsRefused(String host) {
        assertThatThrownBy(() -> new DatabaseConnection(DatabaseDialect.MYSQL, host, 3306, "modl", false, "modl", "secret"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"modl?autoDeserialize=true", "modl;x=y", "mo dl", "modl/other", ""})
    void aDatabaseNameThatCouldChangeTheUrlIsRefused(String database) {
        assertThatThrownBy(() -> new DatabaseConnection(DatabaseDialect.MYSQL, "db", 3306, database, false, "modl", "secret"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void portsUsersAndPasswordsAreBounded() {
        assertThatThrownBy(() -> new DatabaseConnection(DatabaseDialect.MYSQL, "db", 0, "modl", false, "modl", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DatabaseConnection(DatabaseDialect.MYSQL, "db", 3306, "modl", false, " ", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DatabaseConnection(DatabaseDialect.MYSQL, "db", 3306, "modl", false, "modl", "a\nb"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new DatabaseConnection(DatabaseDialect.MYSQL, "db", 3306, "modl", false, "modl", "").password()).isNull();
    }

    @Test
    void theBuiltInDatabaseIgnoresEveryField() {
        DatabaseConnection builtIn = new DatabaseConnection(DatabaseDialect.SQLITE, "ignored", 1, "ignored", true, "user", "secret");

        assertThat(builtIn).isEqualTo(DatabaseConnection.builtIn());
        assertThat(builtIn.location()).isEqualTo(DatabaseLocation.BUILT_IN);
        assertThat(builtIn.properties(BASE).externalUrl()).isEmpty();
    }

    @Test
    void locationsCompareHostsIgnoringCaseAndParseConfiguredUrls() {
        DatabaseConnection connection = new DatabaseConnection(DatabaseDialect.MARIADB, "DB.Example.com", 3307, "modl", false, "modl",
            null);

        assertThat(connection.location()).isEqualTo(DatabaseLocation.ofUrl(DatabaseDialect.MARIADB,
            "jdbc:mariadb://db.example.com:3307/modl?sslMode=verify-full"));
        assertThat(DatabaseLocation.ofUrl(DatabaseDialect.MYSQL, "jdbc:mysql://[::1]:3306/modl").host()).isEqualTo("::1");
        assertThat(connection.describe()).isEqualTo("MariaDB at db.example.com:3307/modl");
    }

    private static String url(DatabaseDialect dialect, String host, boolean tls) {
        return new DatabaseConnection(dialect, host, 3306, "modl", tls, "modl", null).properties(BASE).externalUrl().orElseThrow();
    }
}
