package gg.modl.backend.database.sql;

import gg.modl.backend.database.ConstraintViolations;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;

public final class SqlExceptionTranslation implements ExecuteListener {
    private final ConstraintViolations violations;

    public SqlExceptionTranslation(ConstraintViolations violations) {
        this.violations = violations;
    }

    @Override
    public void exception(ExecuteContext context) {
        RuntimeException failure = context.exception();
        if (failure != null) {
            context.exception(violations.translate(failure));
        }
    }
}
