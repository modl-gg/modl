package gg.modl.backend.knowledgebase.repository;

import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_ARTICLES;

import gg.modl.backend.content.ContentRows;
import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.FreeText;
import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.Indexes;
import gg.modl.backend.database.jooq.tables.records.KnowledgebaseArticlesRecord;
import gg.modl.backend.knowledgebase.data.KnowledgebaseArticle;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqKnowledgebaseArticleRepository implements KnowledgebaseArticleRepository {
    private static final List<SortField<?>> DISPLAY_ORDER = List.of(
        KNOWLEDGEBASE_ARTICLES.ORDINAL.asc(), KNOWLEDGEBASE_ARTICLES.CREATED_AT.asc(), KNOWLEDGEBASE_ARTICLES.ID.asc());
    private static final int NO_ORDINAL = -1;

    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final InsertIfAbsent insertIfAbsent;

    @Override
    public void insert(KnowledgebaseArticle article) {
        KnowledgebaseArticlesRecord row = new KnowledgebaseArticlesRecord();
        row.setId(TextLimits.require(KNOWLEDGEBASE_ARTICLES.ID, article.getId()));
        row.setCategoryId(TextLimits.require(KNOWLEDGEBASE_ARTICLES.CATEGORY_ID, article.getCategoryId()));
        row.setTitle(TextLimits.require(KNOWLEDGEBASE_ARTICLES.TITLE, article.getTitle()));
        row.setSlug(TextLimits.require(KNOWLEDGEBASE_ARTICLES.SLUG, article.getSlug()));
        row.setContent(article.getContent());
        row.setOrdinal(article.getOrdinal());
        row.setIsVisible(article.isVisible());
        row.setIsStarter(article.isStarter());
        row.setCreatedAt(article.getCreatedAt());
        row.setUpdatedAt(article.getUpdatedAt());
        if (!insertIfAbsent.on(KNOWLEDGEBASE_ARTICLES, Indexes.UQ_KNOWLEDGEBASE_ARTICLES_SLUG).insert(row)) {
            throw new ConcurrentUpdateException("Another article took the slug " + article.getSlug());
        }
    }

    @Override
    public List<KnowledgebaseArticle> findAll() {
        return findOrdered(DSL.noCondition());
    }

    @Override
    public List<KnowledgebaseArticle> findByCategoryOrdered(String categoryId) {
        return findOrdered(KNOWLEDGEBASE_ARTICLES.CATEGORY_ID.eq(categoryId));
    }

    @Override
    public List<KnowledgebaseArticle> findVisibleByCategoryOrdered(String categoryId) {
        return findOrdered(KNOWLEDGEBASE_ARTICLES.CATEGORY_ID.eq(categoryId).and(KNOWLEDGEBASE_ARTICLES.IS_VISIBLE.eq(true)));
    }

    @Override
    public List<KnowledgebaseArticle> findVisibleByCategoryIdsOrdered(Collection<String> categoryIds) {
        return InClauseChunks.<String, KnowledgebaseArticle>collect(categoryIds, chunk -> findOrdered(
            KNOWLEDGEBASE_ARTICLES.CATEGORY_ID.in(chunk).and(KNOWLEDGEBASE_ARTICLES.IS_VISIBLE.eq(true))));
    }

    @Override
    public Optional<KnowledgebaseArticle> findById(String id) {
        return findOne(KNOWLEDGEBASE_ARTICLES.ID.eq(id));
    }

    @Override
    public Optional<KnowledgebaseArticle> findBySlug(String slug) {
        return findOne(KNOWLEDGEBASE_ARTICLES.SLUG.eq(slug));
    }

    @Override
    public int findMaxOrdinalInCategory(String categoryId) {
        return dsl.select(DSL.coalesce(DSL.max(KNOWLEDGEBASE_ARTICLES.ORDINAL), NO_ORDINAL))
            .from(KNOWLEDGEBASE_ARTICLES)
            .where(KNOWLEDGEBASE_ARTICLES.CATEGORY_ID.eq(categoryId))
            .fetchSingle()
            .value1();
    }

    @Override
    public boolean existsBySlug(String slug, @Nullable String excludedId) {
        Condition sameSlug = KNOWLEDGEBASE_ARTICLES.SLUG.eq(slug);
        Condition taken = excludedId == null ? sameSlug : sameSlug.and(KNOWLEDGEBASE_ARTICLES.ID.ne(excludedId));
        return dsl.fetchExists(KNOWLEDGEBASE_ARTICLES, taken);
    }

    @Override
    public Optional<KnowledgebaseArticle> update(String id, KnowledgebaseArticlePatch patch) {
        Map<Field<?>, Object> changes = new LinkedHashMap<>();
        changes.put(KNOWLEDGEBASE_ARTICLES.UPDATED_AT, patch.updatedAt());
        ContentRows.putIfPresent(changes, KNOWLEDGEBASE_ARTICLES.TITLE,
            TextLimits.requireIfPresent(KNOWLEDGEBASE_ARTICLES.TITLE, patch.title()));
        ContentRows.putIfPresent(changes, KNOWLEDGEBASE_ARTICLES.SLUG,
            TextLimits.requireIfPresent(KNOWLEDGEBASE_ARTICLES.SLUG, patch.slug()));
        ContentRows.putIfPresent(changes, KNOWLEDGEBASE_ARTICLES.CONTENT, patch.content());
        ContentRows.putIfPresent(changes, KNOWLEDGEBASE_ARTICLES.IS_VISIBLE, patch.visible());
        if (patch.changesText()) {
            changes.put(KNOWLEDGEBASE_ARTICLES.IS_STARTER, false);
        }
        dsl.update(KNOWLEDGEBASE_ARTICLES).set(changes).where(KNOWLEDGEBASE_ARTICLES.ID.eq(id)).execute();
        return findById(id);
    }

    @Override
    public boolean deleteById(String id) {
        return dsl.deleteFrom(KNOWLEDGEBASE_ARTICLES).where(KNOWLEDGEBASE_ARTICLES.ID.eq(id)).execute() > 0;
    }

    @Override
    public long deleteByCategoryId(String categoryId) {
        return dsl.deleteFrom(KNOWLEDGEBASE_ARTICLES).where(KNOWLEDGEBASE_ARTICLES.CATEGORY_ID.eq(categoryId)).execute();
    }

    @Override
    public List<KnowledgebaseArticle> searchVisible(String searchQuery, int limit) {
        Condition matches = FreeText.contains(KNOWLEDGEBASE_ARTICLES.TITLE, searchQuery)
            .or(FreeText.contains(KNOWLEDGEBASE_ARTICLES.CONTENT, searchQuery));
        return unitOfWork.report(() -> dsl.selectFrom(KNOWLEDGEBASE_ARTICLES)
            .where(KNOWLEDGEBASE_ARTICLES.IS_VISIBLE.eq(true).and(matches))
            .orderBy(DISPLAY_ORDER)
            .limit(limit)
            .fetch()
            .map(JooqKnowledgebaseArticleRepository::toArticle));
    }

    @Override
    public void reorder(String categoryId, List<String> ids) {
        ContentRows.renumber(dsl, KNOWLEDGEBASE_ARTICLES, KNOWLEDGEBASE_ARTICLES.ID, KNOWLEDGEBASE_ARTICLES.ORDINAL,
            ids, KNOWLEDGEBASE_ARTICLES.CATEGORY_ID.eq(categoryId));
    }

    private List<KnowledgebaseArticle> findOrdered(Condition condition) {
        return dsl.selectFrom(KNOWLEDGEBASE_ARTICLES)
            .where(condition)
            .orderBy(DISPLAY_ORDER)
            .fetch()
            .map(JooqKnowledgebaseArticleRepository::toArticle);
    }

    private Optional<KnowledgebaseArticle> findOne(Condition condition) {
        return dsl.selectFrom(KNOWLEDGEBASE_ARTICLES)
            .where(condition)
            .fetchOptional()
            .map(JooqKnowledgebaseArticleRepository::toArticle);
    }

    private static KnowledgebaseArticle toArticle(KnowledgebaseArticlesRecord row) {
        return KnowledgebaseArticle.builder()
            .id(row.getId())
            .title(row.getTitle())
            .slug(row.getSlug())
            .content(row.getContent())
            .categoryId(row.getCategoryId())
            .ordinal(row.getOrdinal())
            .isVisible(row.getIsVisible())
            .starter(row.getIsStarter())
            .createdAt(row.getCreatedAt())
            .updatedAt(row.getUpdatedAt())
            .build();
    }
}
