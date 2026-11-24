package gg.modl.backend.database.sql;

import gg.modl.backend.database.StorableText;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.tools.jdbc.DefaultPreparedStatement;

final class StorableTextGuard implements ExecuteListener {
    @Override
    public void prepareEnd(ExecuteContext context) {
        PreparedStatement statement = context.statement();
        if (statement != null) {
            context.statement(new CheckedStatement(statement));
        }
    }

    private static final class CheckedStatement extends DefaultPreparedStatement {
        private CheckedStatement(PreparedStatement delegate) {
            super(delegate);
        }

        @Override
        public void setString(int parameterIndex, String value) throws SQLException {
            super.setString(parameterIndex, StorableText.require(value));
        }

        @Override
        public void setNString(int parameterIndex, String value) throws SQLException {
            super.setNString(parameterIndex, StorableText.require(value));
        }
    }
}
