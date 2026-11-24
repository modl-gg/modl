package gg.modl.backend.knowledgebase.repository;

import gg.modl.backend.knowledgebase.data.KnowledgebaseArticle;
import org.jspecify.annotations.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface KnowledgebaseArticleRepository {
    void insert(KnowledgebaseArticle article);

    List<KnowledgebaseArticle> findAll();

    List<KnowledgebaseArticle> findByCategoryOrdered(String categoryId);

    List<KnowledgebaseArticle> findVisibleByCategoryOrdered(String categoryId);

    List<KnowledgebaseArticle> findVisibleByCategoryIdsOrdered(Collection<String> categoryIds);

    Optional<KnowledgebaseArticle> findById(String id);

    Optional<KnowledgebaseArticle> findBySlug(String slug);

    int findMaxOrdinalInCategory(String categoryId);

    boolean existsBySlug(String slug, @Nullable String excludedId);

    Optional<KnowledgebaseArticle> update(String id, KnowledgebaseArticlePatch patch);

    boolean deleteById(String id);

    long deleteByCategoryId(String categoryId);

    List<KnowledgebaseArticle> searchVisible(String searchQuery, int limit);

    void reorder(String categoryId, List<String> ids);
}
