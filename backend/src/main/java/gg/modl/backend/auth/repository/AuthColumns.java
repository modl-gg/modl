package gg.modl.backend.auth.repository;

import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.email.EmailAddressUtil;
import org.jooq.Condition;
import org.jooq.TableField;
import org.jspecify.annotations.Nullable;

final class AuthColumns {
    private AuthColumns() {
    }

    static Condition matchesIdentity(TableField<?, String> field, @Nullable String email) {
        return ColumnLookups.matches(field, EmailAddressUtil.normalize(email));
    }

    static String identity(TableField<?, String> field, String email) {
        return TextLimits.require(field, EmailAddressUtil.requireNormalized(email));
    }

    static @Nullable String identityIfPresent(TableField<?, String> field, @Nullable String email) {
        return TextLimits.requireIfPresent(field, EmailAddressUtil.normalize(email));
    }
}
