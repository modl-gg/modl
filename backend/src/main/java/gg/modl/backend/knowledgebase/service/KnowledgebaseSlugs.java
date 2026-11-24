package gg.modl.backend.knowledgebase.service;

import com.github.slugify.Slugify;
import gg.modl.backend.content.CappedText;
import gg.modl.backend.database.TextLimits;
import java.util.function.Predicate;
import org.jooq.TableField;

final class KnowledgebaseSlugs {
    private static final Slugify SLUGIFY = Slugify.builder().build();

    private KnowledgebaseSlugs() {
    }

    static String of(String text, TableField<?, String> column) {
        return fitted(SLUGIFY.slugify(text), "", TextLimits.limit(column));
    }

    static String unused(String text, TableField<?, String> column, Predicate<String> taken) {
        String base = SLUGIFY.slugify(text);
        int limit = TextLimits.limit(column);
        String slug = fitted(base, "", limit);
        for (int suffix = 1; taken.test(slug); suffix++) {
            slug = fitted(base, "-" + suffix, limit);
        }
        return slug;
    }

    private static String fitted(String base, String suffix, int limit) {
        return CappedText.of(base, limit - suffix.length()) + suffix;
    }
}
