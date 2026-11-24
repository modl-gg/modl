package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseBusyException;
import gg.modl.backend.database.UnitFrame;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Map;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

final class RoutingDataSource extends AbstractRoutingDataSource {
    private enum Route {
        WRITER,
        READER
    }

    private final InstanceFence fence;

    RoutingDataSource(ConnectionPools pools, InstanceFence fence) {
        this.fence = fence;
        setTargetDataSources(Map.of(Route.WRITER, pools.writer(), Route.READER, pools.reader()));
        setLenientFallback(false);
        afterPropertiesSet();
    }

    @Override
    public Connection getConnection() throws SQLException {
        fence.requireServing();
        try {
            return super.getConnection();
        } catch (SQLTransientConnectionException e) {
            throw new DatabaseBusyException("No database connection became free in time", DatabaseBusyException.POOL_RETRY_AFTER, e);
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLException("Credentials come from the modl database settings");
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        throw new SQLException("The modl database connections are reachable only through the routing data source");
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return false;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return UnitFrame.routesToWriter() ? Route.WRITER : Route.READER;
    }
}
