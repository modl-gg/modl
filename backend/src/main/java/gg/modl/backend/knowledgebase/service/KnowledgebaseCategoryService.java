package gg.modl.backend.knowledgebase.service;

import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_CATEGORIES;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.validation.RequiredText;
import gg.modl.backend.knowledgebase.data.KnowledgebaseCategory;
import gg.modl.backend.knowledgebase.repository.KnowledgebaseCategoryPatch;
import gg.modl.backend.knowledgebase.repository.KnowledgebaseCategoryRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.CreateCategoryRequest;
import gg.modl.proto.modl.v1.UpdateCategoryRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KnowledgebaseCategoryService {
    private final KnowledgebaseCategoryRepository categoryRepository;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public List<KnowledgebaseCategory> getAllCategories(Server server) {
        return categoryRepository.findAllOrdered();
    }

    public List<KnowledgebaseCategory> getVisibleCategories(Server server) {
        return categoryRepository.findVisibleOrdered();
    }

    public Optional<KnowledgebaseCategory> getCategoryById(Server server, String id) {
        return categoryRepository.findById(id);
    }

    public List<KnowledgebaseCategory> getCategoriesByIds(Server server, Collection<String> ids) {
        return categoryRepository.findByIds(ids);
    }

    public KnowledgebaseCategory createCategory(Server server, CreateCategoryRequest request) {
        return unitOfWork.write(() -> insertCategory(request));
    }

    private KnowledgebaseCategory insertCategory(CreateCategoryRequest request) {
        String id = EntityIds.next();
        Instant now = clock.instant();
        categoryRepository.insert(KnowledgebaseCategory.builder()
            .id(id)
            .name(request.getName())
            .slug(KnowledgebaseSlugs.of(request.getName(), KNOWLEDGEBASE_CATEGORIES.SLUG))
            .description(request.hasDescription() ? request.getDescription() : null)
            .ordinal(categoryRepository.findMaxOrdinal() + 1)
            .isVisible(true)
            .createdAt(now)
            .updatedAt(now)
            .build());
        return categoryRepository.findById(id).orElseThrow();
    }

    public Optional<KnowledgebaseCategory> updateCategory(Server server, String id, UpdateCategoryRequest request) {
        String name = RequiredText.notBlankIfPresent(request.hasName() ? request.getName() : null, "The category name");
        KnowledgebaseCategoryPatch patch = KnowledgebaseCategoryPatch.builder()
            .name(name)
            .slug(name != null ? KnowledgebaseSlugs.of(name, KNOWLEDGEBASE_CATEGORIES.SLUG) : null)
            .description(request.hasDescription() ? request.getDescription() : null)
            .visible(request.hasIsVisible() ? request.getIsVisible() : null)
            .updatedAt(clock.instant())
            .build();
        return unitOfWork.write(() -> categoryRepository.update(id, patch));
    }

    public boolean deleteCategory(Server server, String id) {
        return unitOfWork.write(() -> categoryRepository.deleteById(id));
    }

    public void reorderCategories(Server server, List<String> ids) {
        unitOfWork.write(() -> categoryRepository.reorder(ids));
    }
}
