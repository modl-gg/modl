package gg.modl.backend.knowledgebase.service;

import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_ARTICLES;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.validation.RequiredText;
import gg.modl.backend.knowledgebase.data.KnowledgebaseArticle;
import gg.modl.backend.knowledgebase.repository.KnowledgebaseArticlePatch;
import gg.modl.backend.knowledgebase.repository.KnowledgebaseArticleRepository;
import gg.modl.backend.knowledgebase.repository.KnowledgebaseCategoryRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.CreateArticleRequest;
import gg.modl.proto.modl.v1.UpdateArticleRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KnowledgebaseArticleService {
    private static final int MAX_SEARCH_RESULTS = 20;

    private final KnowledgebaseArticleRepository articleRepository;
    private final KnowledgebaseCategoryRepository categoryRepository;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public List<KnowledgebaseArticle> getArticlesByCategory(Server server, String categoryId) {
        return articleRepository.findByCategoryOrdered(categoryId);
    }

    public Map<String, List<KnowledgebaseArticle>> getAllArticlesGroupedByCategory(Server server) {
        return groupedByCategory(articleRepository.findAll());
    }

    public List<KnowledgebaseArticle> getVisibleArticlesByCategory(Server server, String categoryId) {
        return articleRepository.findVisibleByCategoryOrdered(categoryId);
    }

    public Map<String, List<KnowledgebaseArticle>> getVisibleArticlesGroupedByCategoryIds(Server server, Collection<String> categoryIds) {
        return groupedByCategory(articleRepository.findVisibleByCategoryIdsOrdered(categoryIds));
    }

    public Optional<KnowledgebaseArticle> getArticleById(Server server, String id) {
        return articleRepository.findById(id);
    }

    public Optional<KnowledgebaseArticle> getArticleBySlug(Server server, String slug) {
        return articleRepository.findBySlug(slug);
    }

    public KnowledgebaseArticle createArticle(Server server, String categoryId, CreateArticleRequest request) {
        return unitOfWork.write(() -> insertArticle(categoryId, request));
    }

    private KnowledgebaseArticle insertArticle(String categoryId, CreateArticleRequest request) {
        if (categoryRepository.findById(categoryId).isEmpty()) {
            throw new ResourceNotFoundException("Category not found");
        }
        String id = EntityIds.next();
        Instant now = clock.instant();
        articleRepository.insert(KnowledgebaseArticle.builder()
            .id(id)
            .title(request.getTitle())
            .slug(unusedSlug(request.getTitle(), null))
            .content(request.getContent())
            .categoryId(categoryId)
            .ordinal(articleRepository.findMaxOrdinalInCategory(categoryId) + 1)
            .isVisible(!request.hasIsVisible() || request.getIsVisible())
            .createdAt(now)
            .updatedAt(now)
            .build());
        return articleRepository.findById(id).orElseThrow();
    }

    public Optional<KnowledgebaseArticle> updateArticle(Server server, String id, UpdateArticleRequest request) {
        return unitOfWork.write(() -> articleRepository.update(id, patchOf(id, request)));
    }

    private KnowledgebaseArticlePatch patchOf(String id, UpdateArticleRequest request) {
        String title = RequiredText.notBlankIfPresent(request.hasTitle() ? request.getTitle() : null, "The article title");
        return KnowledgebaseArticlePatch.builder()
            .title(title)
            .slug(title != null ? unusedSlug(title, id) : null)
            .content(RequiredText.notBlankIfPresent(request.hasContent() ? request.getContent() : null, "The article content"))
            .visible(request.hasIsVisible() ? request.getIsVisible() : null)
            .updatedAt(clock.instant())
            .build();
    }

    public boolean deleteArticle(Server server, String id) {
        return unitOfWork.write(() -> articleRepository.deleteById(id));
    }

    public List<KnowledgebaseArticle> searchArticles(Server server, String searchQuery) {
        return articleRepository.searchVisible(searchQuery, MAX_SEARCH_RESULTS);
    }

    public void reorderArticles(Server server, String categoryId, List<String> ids) {
        unitOfWork.write(() -> articleRepository.reorder(categoryId, ids));
    }

    private String unusedSlug(String title, @Nullable String excludedId) {
        return KnowledgebaseSlugs.unused(title, KNOWLEDGEBASE_ARTICLES.SLUG,
            slug -> articleRepository.existsBySlug(slug, excludedId));
    }

    private static Map<String, List<KnowledgebaseArticle>> groupedByCategory(List<KnowledgebaseArticle> articles) {
        return articles.stream().collect(Collectors.groupingBy(KnowledgebaseArticle::getCategoryId));
    }
}
