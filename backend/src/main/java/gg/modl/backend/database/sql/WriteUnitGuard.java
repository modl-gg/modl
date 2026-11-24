package gg.modl.backend.database.sql;

import gg.modl.backend.database.UnitFrame;
import org.jooq.DDLQuery;
import org.jooq.Delete;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.ExecuteType;
import org.jooq.Insert;
import org.jooq.Merge;
import org.jooq.Query;
import org.jooq.Update;

public final class WriteUnitGuard implements ExecuteListener {
    @Override
    public void start(ExecuteContext context) {
        if (!UnitFrame.routesToWriter() && changesData(context)) {
            throw new IllegalStateException("A data change ran outside a write unit: " + context.type());
        }
    }

    private static boolean changesData(ExecuteContext context) {
        Query query = context.query();
        if (query instanceof Insert || query instanceof Update || query instanceof Delete || query instanceof Merge
            || query instanceof DDLQuery) {
            return true;
        }
        return context.type() != ExecuteType.READ;
    }
}
