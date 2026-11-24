package gg.modl.backend.knowledgebase.repository;

import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_CATEGORIES;

import gg.modl.backend.content.ContentRows;
import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.jooq.tables.records.KnowledgebaseCategoriesRecord;
import gg.modl.backend.knowledgebase.data.KnowledgebaseCategory;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqKnowledgebaseCategoryRepository implements KnowledgebaseCategoryRepository {
    private static final List<SortField<?>> DISPLAY_ORDER = List.of(
        KNOWLEDGEBASE_CATEGORIES.ORDINAL.asc(), KNOWLEDGEBASE_CATEGORIES.CREATED_AT.asc(), KNOWLEDGEBASE_CATEGORIES.ID.asc());
    private static final int NO_ORDINAL = -1;

    private final DSLContext dsl;

    @Override
    public void insert(KnowledgebaseCategory category) {
        KnowledgebaseCategoriesRecord row = new KnowledgebaseCategoriesRecord();
        row.setId(TextLimits.require(KNOWLEDGEBASE_CATEGORIES.ID, category.getId()));
        row.setName(TextLimits.require(KNOWLEDGEBASE_CATEGORIES.NAME, category.getName()));
        row.setSlug(TextLimits.require(KNOWLEDGEBASE_CATEGORIES.SLUG, category.getSlug()));
        row.setDescription(category.getDescription());
        row.setOrdinal(category.getOrdinal());
        row.setIsVisible(category.isVisible());
        row.setCreatedAt(category.getCreatedAt());
        row.setUpdatedAt(category.getUpdatedAt());
        dsl.insertInto(KNOWLEDGEBASE_CATEGORIES).set(row).execute();
    }

    @Override
    public boolean hasAny() {
        return dsl.fetchExists(KNOWLEDGEBASE_CATEGORIES);
    }

    @Override
    public List<KnowledgebaseCategory> findAllOrdered() {
        return dsl.selectFrom(KNOWLEDGEBASE_CATEGORIES)
            .orderBy(DISPLAY_ORDER)
            .fetch()
            .map(JooqKnowledgebaseCategoryRepository::toCategory);
    }

    @Override
    public List<KnowledgebaseCategory> findVisibleOrdered() {
        return dsl.selectFrom(KNOWLEDGEBASE_CATEGORIES)
            .where(KNOWLEDGEBASE_CATEGORIES.IS_VISIBLE.eq(true))
            .orderBy(DISPLAY_ORDER)
            .fetch()
            .map(JooqKnowledgebaseCategoryRepository::toCategory);
    }

    @Override
    public Optional<KnowledgebaseCategory> findById(String id) {
        return dsl.selectFrom(KNOWLEDGEBASE_CATEGORIES)
            .where(KNOWLEDGEBASE_CATEGORIES.ID.eq(id))
            .fetchOptional()
            .map(JooqKnowledgebaseCategoryRepository::toCategory);
    }

    @Override
    public List<KnowledgebaseCategory> findByIds(Collection<String> ids) {
        return InClauseChunks.<String, KnowledgebaseCategory>collect(ids, chunk -> dsl.selectFrom(KNOWLEDGEBASE_CATEGORIES)
            .where(KNOWLEDGEBASE_CATEGORIES.ID.in(chunk))
            .orderBy(DISPLAY_ORDER)
            .fetch()
            .map(JooqKnowledgebaseCategoryRepository::toCategory));
    }

    @Override
    public int findMaxOrdinal() {
        return dsl.select(DSL.coalesce(DSL.max(KNOWLEDGEBASE_CATEGORIES.ORDINAL), NO_ORDINAL))
            .from(KNOWLEDGEBASE_CATEGORIES)
            .fetchSingle()
            .value1();
    }

    @Override
    public Optional<KnowledgebaseCategory> update(String id, KnowledgebaseCategoryPatch patch) {
        Map<Field<?>, Object> changes = new LinkedHashMap<>();
        changes.put(KNOWLEDGEBASE_CATEGORIES.UPDATED_AT, patch.updatedAt());
        ContentRows.putIfPresent(changes, KNOWLEDGEBASE_CATEGORIES.NAME,
            TextLimits.requireIfPresent(KNOWLEDGEBASE_CATEGORIES.NAME, patch.name()));
        ContentRows.putIfPresent(changes, KNOWLEDGEBASE_CATEGORIES.SLUG,
            TextLimits.requireIfPresent(KNOWLEDGEBASE_CATEGORIES.SLUG, patch.slug()));
        ContentRows.putIfPresent(changes, KNOWLEDGEBASE_CATEGORIES.DESCRIPTION, patch.description());
        ContentRows.putIfPresent(changes, KNOWLEDGEBASE_CATEGORIES.IS_VISIBLE, patch.visible());
        dsl.update(KNOWLEDGEBASE_CATEGORIES).set(changes).where(KNOWLEDGEBASE_CATEGORIES.ID.eq(id)).execute();
        return findById(id);
    }

    @Override
    public boolean deleteById(String id) {
        return dsl.deleteFrom(KNOWLEDGEBASE_CATEGORIES).where(KNOWLEDGEBASE_CATEGORIES.ID.eq(id)).execute() > 0;
    }

    @Override
    public void reorder(List<String> ids) {
        ContentRows.renumber(dsl, KNOWLEDGEBASE_CATEGORIES, KNOWLEDGEBASE_CATEGORIES.ID, KNOWLEDGEBASE_CATEGORIES.ORDINAL,
            ids, DSL.noCondition());
    }

    private static KnowledgebaseCategory toCategory(KnowledgebaseCategoriesRecord row) {
        return KnowledgebaseCategory.builder()
            .id(row.getId())
            .name(row.getName())
            .slug(row.getSlug())
            .description(row.getDescription())
            .ordinal(row.getOrdinal())
            .isVisible(row.getIsVisible())
            .createdAt(row.getCreatedAt())
            .updatedAt(row.getUpdatedAt())
            .build();
    }
}
